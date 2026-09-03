package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
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
        val downloadJobs = KeyedJobRegistry<Int>()
        val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()

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
                    if (Settings.disableReaderNetworkCache.value) return
                    pages.forEach { ensureDownload(it, interactive = false) }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    ensureDownload(index, interactive = true) {
                        notifySourceReady(index, orgImg)
                    }
                }

                override fun onNavigation(demand: ReaderDemand) {
                    cancelStaleDownloads(demand.sourcePages, demand.decodedPages)
                }

                private fun isLibHdrCandidate(name: String): Boolean = HdrConvertCache.usesNetworkLibConvert(name)

                private fun cancelStaleDownloads(sourcePages: Set<Int>, decodedPages: Set<Int>) {
                    readyWaiters.forEach { idx, _ -> if (idx !in decodedPages) readyWaiters.remove(idx) }
                    // ConcurrentHashMap.forEach — avoid entries.toList() iterator race on Android.
                    downloadJobs.cancelOutside(sourcePages)
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

                    val existing = downloadJobs.owner(index)
                    if (existing != null && !existing.isCompleted) return

                    val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try {
                            // Authoritative disk check on IO (StrictMode + LRU correctness).
                            if (WebDavCache.isPageCachedOnDisk(cache)) {
                                dispatchReady(index)
                                return@launch
                            }
                            val slots = when {
                                interactive || readyWaiters[index]?.isNotEmpty() == true -> interactiveSlots
                                isLibHdrCandidate(name) -> libHdrPrefetchSlots
                                else -> prefetchSlots
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
                            val runningJob = coroutineContext[Job]
                            val owns = downloadJobs.owns(index, runningJob)
                            if (owns) {
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty()) {
                                    waiters.forEach { addReadyWaiter(index, it) }
                                    downloadJobs.release(index, runningJob)
                                    ensureDownload(index, interactive = true)
                                }
                            }
                            throw e
                        } catch (e: Throwable) {
                            if (downloadJobs.owns(index, coroutineContext[Job])) {
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty()) {
                                    notifyPageFailed(index, e.message)
                                }
                            }
                        } finally {
                            downloadJobs.release(index, coroutineContext[Job])
                        }
                    }
                    val ownsSlot = downloadJobs.register(index, job)
                    if (ownsSlot) job.start() else job.cancel()
                }
            },
        )
        block(loader)
    }
}
