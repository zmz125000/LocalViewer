package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.util.FileUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.kt.install
import okio.Path

/**
 * SMB folder reader with seek-friendly downloads:
 * - Connection pool ([SmbGateway.maxConnectionsPerSource]) so a seekbar jump can start
 *   on a free TCP session without waiting for the current page transfer to finish.
 * - One reserved interactive slot for [onRequest]; prefetch uses the remaining slots
 *   (when pool size is 1, both share the same slot).
 * - Per-file mutex in [SmbCache] joins overlapping downloads (small jump / prefetch race).
 * - Large jumps cancel far-away prefetch jobs so they stop borrowing pool connections.
 */
suspend inline fun <T> useSmbFolderPageLoader(
    source: SmbSourceEntity,
    remoteDir: String,
    imageFileNames: List<String>,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(imageFileNames.isNotEmpty()) { "No images in SMB folder" }
        val password = SmbPasswordStore.get(source.id)
        val size = imageFileNames.size
        val poolSize = SmbGateway.maxConnectionsPerSource()
        // Reserve 1 connection for the page the user is looking at / just seeked to.
        val interactiveSlots = Semaphore(1)
        val prefetchSlots = if (poolSize <= 1) {
            interactiveSlots
        } else {
            Semaphore(poolSize - 1)
        }
        // In-flight downloads by page index — join small-jump overlap, cancel large jumps.
        val downloadJobs = ConcurrentHashMap<Int, Job>()
        // Pages within this distance of the target keep running; farther jobs are cancelled.
        val keepWindow = 4

        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title by lazy {
                    info?.title
                        ?: remoteDir.substringAfterLast('/').substringAfterLast('\\')
                            .ifEmpty { source.displayName }
                }

                override fun getImageExtension(index: Int) =
                    FileUtils.getExtensionFromFilename(imageFileNames[index])

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    val cached = SmbCache.cachePath(source.id, remoteDir, imageFileNames[index])
                    check(SmbCache.isCached(cached)) { "Not cached" }
                    cached sendTo file
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val name = imageFileNames[index]
                    val path = SmbCache.cachePath(source.id, remoteDir, name)
                    check(SmbCache.isCached(path)) { "SMB page $index not downloaded" }
                    return object : PathSource {
                        override val source = path
                        override val type by lazy {
                            FileUtils.getExtensionFromFilename(name)!!
                        }

                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    pages.forEach { index ->
                        ensureDownload(index, interactive = false)
                    }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    // Large seek: drop distant prefeches so pool capacity frees for the new region.
                    cancelDistantDownloads(index)
                    ensureDownload(index, interactive = true) {
                        notifySourceReady(index)
                    }
                }

                private fun cancelDistantDownloads(center: Int) {
                    val snapshot = downloadJobs.entries.toList()
                    for ((idx, job) in snapshot) {
                        if (kotlin.math.abs(idx - center) > keepWindow) {
                            job.cancel()
                            downloadJobs.remove(idx, job)
                        }
                    }
                }

                /**
                 * Start or join a download for [index].
                 * - Small jump / same page: reuses the existing job (and [SmbCache] path lock).
                 * - Interactive: uses reserved pool capacity so seek does not queue behind prefetch.
                 */
                private fun ensureDownload(
                    index: Int,
                    interactive: Boolean,
                    onReady: (() -> Unit)? = null,
                ) {
                    if (index !in 0 until size) return
                    val name = imageFileNames[index]
                    val cache = SmbCache.cachePath(source.id, remoteDir, name)
                    if (SmbCache.isCached(cache)) {
                        onReady?.invoke()
                        return
                    }
                    // Overlap: join in-flight job for this page (common on small seeks).
                    downloadJobs[index]?.let { existing ->
                        if (existing.isActive) {
                            if (onReady != null) {
                                scope.launch(Dispatchers.IO) {
                                    existing.join()
                                    if (SmbCache.isCached(cache)) onReady()
                                    else {
                                        // Previous job failed/cancelled — retry as interactive.
                                        ensureDownload(index, interactive = true, onReady = onReady)
                                    }
                                }
                            }
                            return
                        }
                    }
                    val job = scope.launch(Dispatchers.IO) {
                        try {
                            val slots = if (interactive) interactiveSlots else prefetchSlots
                            slots.withPermit {
                                downloadToCache(index)
                            }
                            if (SmbCache.isCached(cache)) {
                                onReady?.invoke()
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // Distant-prefetch cancel or loader teardown — ignore.
                        } catch (e: Throwable) {
                            // Never rethrow: a failed child would cancel the whole reader scope.
                            if (interactive) {
                                notifyPageFailed(index, e.message)
                            }
                        } finally {
                            downloadJobs.remove(index)
                        }
                    }
                    downloadJobs[index] = job
                }

                private suspend fun downloadToCache(index: Int) {
                    val name = imageFileNames[index]
                    val cache = SmbCache.cachePath(source.id, remoteDir, name)
                    if (SmbCache.isCached(cache)) return
                    val rel = if (remoteDir.isEmpty()) name else "$remoteDir/$name"
                    // Per-path mutex: two connections never write the same cache file.
                    SmbCache.downloadIfNeeded(cache) { out ->
                        SmbGateway.downloadFile(source, password, rel, out)
                    }
                }
            },
        )
        block(loader)
    }
}
