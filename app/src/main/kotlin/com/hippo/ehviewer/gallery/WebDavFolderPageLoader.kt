package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.kt.install
import okio.Path

/**
 * WebDAV folder reader — same waiter/prefetch shape as SMB, without TCP pool.
 * HTTP client multiplexes; download fan-out is capped inside [WebDavClient].
 * Convert-mode lib-HDR/avif: interactive + 1 prefetch (B1 depth 2).
 * Direct-Bitmap mode: same prefetch slots as non-lib (cache original only).
 */
suspend inline fun <T> useWebDavFolderPageLoader(
    source: WebDavSourceEntity,
    remoteDir: String,
    imageFileNames: List<String>,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(imageFileNames.isNotEmpty()) { "No images in WebDAV folder" }
        val password = WebDavPasswordStore.get(source.id)
        val size = imageFileNames.size
        val interactiveSlots = Semaphore(1)
        val prefetchSlots = Semaphore(3)
        // Cap concurrent lib downloads; full UHDR convert is serial in HdrConvertCache.
        val libHdrPrefetchSlots = Semaphore(2)
        val downloadJobs = ConcurrentHashMap<Int, Job>()
        val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
        val keepWindow = 4
        val libHdrKeepWindow = 2

        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title by lazy {
                    info?.title
                        ?: remoteDir.substringAfterLast('/').ifEmpty { source.displayName }
                }

                override fun getImageExtension(index: Int) = FileUtils.getExtensionFromFilename(imageFileNames[index])

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    val primary = WebDavCache.cachePath(source.id, remoteDir, imageFileNames[index])
                    val cached = WebDavCache.resolveReaderPath(primary)
                    check(WebDavCache.isCachedOnDisk(cached)) { "Not cached" }
                    cached sendTo file
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val name = imageFileNames[index]
                    val primary = WebDavCache.cachePath(source.id, remoteDir, name)
                    val path = WebDavCache.resolveReaderPath(primary)
                    check(WebDavCache.isCachedOnDisk(path)) { "WebDAV page $index not downloaded" }
                    return object : PathSource {
                        override val source = path
                        override val type by lazy {
                            FileUtils.getExtensionFromFilename(path.name)
                                ?: FileUtils.getExtensionFromFilename(name)
                                ?: "jpg"
                        }

                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    pages.forEach { ensureDownload(it, interactive = false) }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    cancelDistantDownloads(index)
                    ensureDownload(index, interactive = true) {
                        notifySourceReady(index, orgImg)
                    }
                }

                private fun isLibHdrCandidate(name: String): Boolean = HdrConvertCache.usesNetworkLibConvert(name)

                private fun cancelDistantDownloads(center: Int) {
                    val centerLib = isLibHdrCandidate(imageFileNames.getOrNull(center).orEmpty())
                    val window = if (centerLib) libHdrKeepWindow else keepWindow
                    for ((idx, job) in downloadJobs.entries.toList()) {
                        if (kotlin.math.abs(idx - center) > window) job.cancel()
                    }
                }

                private fun addReadyWaiter(index: Int, onReady: () -> Unit) {
                    readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                }

                private fun takeReadyWaiters(index: Int): List<() -> Unit> = readyWaiters.remove(index)?.toList().orEmpty()

                private fun dispatchReady(index: Int) {
                    takeReadyWaiters(index).forEach { runCatching { it() } }
                }

                private fun ensureDownload(
                    index: Int,
                    interactive: Boolean,
                    onReady: (() -> Unit)? = null,
                ) {
                    if (index !in 0 until size) return
                    val name = imageFileNames[index]
                    val cache = WebDavCache.cachePath(source.id, remoteDir, name)
                    // Never probe disk here — onRequest/retryPage run on main (lifecycle).
                    if (onReady == null && WebDavCache.isPageCached(cache)) {
                        return
                    }
                    if (onReady != null) addReadyWaiter(index, onReady)

                    val existing = downloadJobs[index]
                    if (existing != null && existing.isActive) return

                    val slots = when {
                        interactive -> interactiveSlots
                        isLibHdrCandidate(name) -> libHdrPrefetchSlots
                        else -> prefetchSlots
                    }
                    val job = launch(Dispatchers.IO) {
                        try {
                            // Authoritative disk check on IO (StrictMode + LRU correctness).
                            if (WebDavCache.isPageCachedOnDisk(cache)) {
                                dispatchReady(index)
                                return@launch
                            }
                            slots.withPermit {
                                if (WebDavCache.isPageCachedOnDisk(cache)) {
                                    dispatchReady(index)
                                    return@withPermit
                                }
                                val remote = if (remoteDir.isEmpty()) name else "$remoteDir/$name"
                                WebDavCache.downloadIfNeeded(cache, originalFileName = name) { out ->
                                    WebDavClient.downloadFile(source, password, remote, out)
                                }
                                if (WebDavCache.isPageCachedOnDisk(cache)) {
                                    dispatchReady(index)
                                } else if (readyWaiters.containsKey(index)) {
                                    notifyPageFailed(index, "WebDAV download incomplete")
                                    takeReadyWaiters(index)
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            val owns = downloadJobs[index] == coroutineContext[Job]
                            if (owns) {
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty()) {
                                    waiters.forEach { addReadyWaiter(index, it) }
                                    ensureDownload(index, interactive = true)
                                }
                            }
                            throw e
                        } catch (e: Throwable) {
                            val waiters = takeReadyWaiters(index)
                            if (waiters.isNotEmpty()) {
                                notifyPageFailed(index, e.message)
                            }
                        } finally {
                            downloadJobs.remove(index, coroutineContext[Job])
                        }
                    }
                    downloadJobs[index] = job
                }
            },
        )
        block(loader)
    }
}
