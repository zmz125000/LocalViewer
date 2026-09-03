package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.util.FileUtils
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
 * SMB folder reader with seek-friendly downloads:
 * - Host pool multiplexes ops ([SmbGateway.maxConcurrentOpsPerHost] ≈ sessions × ops/session).
 * - One reserved interactive slot for [onRequest]; prefetch uses the remaining slots
 *   so a seek does not wait behind every prefetch transfer.
 * - Per-file mutex in [SmbCache] joins overlapping downloads (small jump / prefetch race).
 * - Large jumps cancel far-away prefetch jobs so they stop holding pool op slots.
 * - UI waiters ([onReady] / notifySourceReady) are registered on a list so cancel/join
 *   races never leave a page spinning forever (manual refresh worked because it
 *   forced a clean onRequest).
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
        val maxOps = SmbGateway.maxConcurrentOpsPerHost().coerceAtLeast(1)
        // Reserve 1 op for the page the user is looking at / just seeked to.
        val interactiveSlots = Semaphore(1)
        val prefetchSlots = if (maxOps <= 1) {
            interactiveSlots
        } else {
            Semaphore(maxOps - 1)
        }
        // B1 convert mode: cap concurrent lib downloads (full convert is serial in
        // HdrConvertCache.fullConvertSlots). Direct-Bitmap uses normal prefetchSlots.
        val libHdrPrefetchSlots = Semaphore(2)
        // In-flight downloads by page index — join small-jump overlap, cancel large jumps.
        val downloadJobs = KeyedJobRegistry<Int>()

        /** UI/decode callbacks waiting for [index] to land in [SmbCache]. */
        val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
        val ramPages = ConcurrentHashMap<Int, ByteArray>()
        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title by lazy {
                    info?.title
                        ?: remoteDir.substringAfterLast('/').substringAfterLast('\\')
                            .ifEmpty { source.displayName }
                }

                override fun getImageExtension(index: Int) = FileUtils.getExtensionFromFilename(imageFileNames[index])

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    ramPages[index]?.let {
                        java.io.File(file.toString()).writeBytes(it)
                        return@runCatching true
                    }
                    val primary = SmbCache.cachePath(source.id, remoteDir, imageFileNames[index])
                    val cached = SmbCache.resolveReaderPath(primary)
                    check(SmbCache.isCachedOnDisk(cached)) { "Not cached" }
                    cached sendTo file
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    ramPages[index]?.let { bytes ->
                        return byteBufferSource(ByteBuffer.wrap(bytes)) {}
                    }
                    val name = imageFileNames[index]
                    val primary = SmbCache.cachePath(source.id, remoteDir, name)
                    val path = SmbCache.resolveReaderPath(primary)
                    // Always re-probe disk: knownPresent can outlive LRU eviction of cover pages.
                    check(SmbCache.isCachedOnDisk(path)) { "SMB page $index not downloaded" }
                    return object : PathSource {
                        override val source = path

                        // Converted Ultra HDR is JPEG; original name may be .jxr
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
                    pages.forEach { index ->
                        ensureDownload(index, interactive = false)
                    }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    ensureDownload(index, interactive = true) {
                        notifySourceReady(index, orgImg)
                    }
                }

                override fun onNavigation(demand: ReaderDemand) {
                    // Reprioritize once per viewport update, not once for every decode-ahead page.
                    cancelStaleDownloads(demand.sourcePages, demand.decodedPages)
                }

                /** Restrict prefetch only when download will RAM→UHDR convert. */
                private fun isLibHdrCandidate(name: String): Boolean = HdrConvertCache.usesNetworkLibConvert(name)

                private fun cancelStaleDownloads(sourcePages: Set<Int>, decodedPages: Set<Int>) {
                    // A waiter represents decode demand. Drop it before cancelling so the
                    // cancellation handler cannot resurrect an obsolete interactive transfer.
                    readyWaiters.forEach { idx, _ -> if (idx !in decodedPages) readyWaiters.remove(idx) }
                    ramPages.keys.toList().forEach { idx ->
                        if (idx !in sourcePages && idx !in decodedPages) ramPages.remove(idx)
                    }
                    // ConcurrentHashMap.forEach (BiConsumer) — never entries/keys iterator.
                    // Android EntryIterator.next can throw NoSuchElementException under concurrent
                    // put/remove; dual-page fires two onRequest close together.
                    downloadJobs.cancelOutside(sourcePages)
                }

                private fun addReadyWaiter(index: Int, onReady: () -> Unit) {
                    readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                }

                private fun takeReadyWaiters(index: Int): List<() -> Unit> = readyWaiters.remove(index)?.toList().orEmpty()

                private fun dispatchReady(index: Int) {
                    takeReadyWaiters(index).forEach { runCatching { it() } }
                }

                /**
                 * Start or join a download for [index].
                 * - Small jump / same page: reuses the existing job; [onReady] is queued.
                 * - Interactive: uses reserved pool capacity so seek does not queue behind prefetch.
                 * - Always completes waiters: success → notifySourceReady; fail/cancel with waiters
                 *   → retry once or [notifyPageFailed] (never silent forever-spinner).
                 */
                private fun ensureDownload(
                    index: Int,
                    interactive: Boolean,
                    onReady: (() -> Unit)? = null,
                ) {
                    if (index !in 0 until size) return
                    val name = imageFileNames[index]
                    val cache = SmbCache.cachePath(source.id, remoteDir, name)
                    val skipDisk = Settings.disableReaderNetworkCache.value
                    // Never probe disk here — onRequest/retryPage run on main (lifecycle
                    // ON_RESUME). Memory-only skip for prefetch when known present.
                    if (onReady == null && !skipDisk && SmbCache.isPageCached(cache)) {
                        return
                    }
                    if (onReady == null && skipDisk && ramPages.containsKey(index)) {
                        return
                    }
                    if (onReady != null) {
                        addReadyWaiter(index, onReady)
                    }
                    val existing = downloadJobs.owner(index)
                    if (existing != null && !existing.isCompleted) {
                        // Waiters already registered; in-flight job will dispatch or retry.
                        return
                    }
                    val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        var needsInteractive = interactive
                        try {
                            val skipDisk = Settings.disableReaderNetworkCache.value
                            if (skipDisk && ramPages.containsKey(index)) {
                                dispatchReady(index)
                                return@launch
                            }
                            // Authoritative disk check on IO (StrictMode + LRU correctness).
                            // Skip mtime touch when not writing cache.
                            if (!skipDisk && SmbCache.isPageCachedOnDisk(cache)) {
                                dispatchReady(index)
                                return@launch
                            }
                            if (skipDisk && SmbCache.isCachedOnDisk(SmbCache.resolveReaderPath(cache))) {
                                dispatchReady(index)
                                return@launch
                            }
                            // Promote to interactive slot if the UI is waiting (joined mid-prefetch).
                            if (readyWaiters[index]?.isNotEmpty() == true) {
                                needsInteractive = true
                            }
                            val nameForSlot = imageFileNames[index]
                            val slots = when {
                                needsInteractive -> interactiveSlots
                                isLibHdrCandidate(nameForSlot) -> libHdrPrefetchSlots
                                else -> prefetchSlots
                            }
                            slots.withPermit {
                                if (skipDisk) {
                                    downloadToRam(index)
                                } else {
                                    downloadToCache(index)
                                }
                            }
                            val ready = if (skipDisk) {
                                ramPages.containsKey(index) ||
                                    SmbCache.isCachedOnDisk(SmbCache.resolveReaderPath(cache))
                            } else {
                                SmbCache.isPageCachedOnDisk(cache)
                            }
                            if (ready) {
                                dispatchReady(index)
                            } else {
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty()) {
                                    notifyPageFailed(index, "Download incomplete")
                                }
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // Lost putIfAbsent must not steal waiters from the in-flight owner.
                            val runningJob = coroutineContext[Job]
                            val owns = downloadJobs.owns(index, runningJob)
                            if (owns) {
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty()) {
                                    waiters.forEach { addReadyWaiter(index, it) }
                                    // Release the cancelled owner before registering its retry.
                                    downloadJobs.release(index, runningJob)
                                    ensureDownload(index, interactive = true)
                                }
                            }
                        } catch (e: Throwable) {
                            // Never rethrow: a failed child would cancel the whole reader scope.
                            if (downloadJobs.owns(index, coroutineContext[Job])) {
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty() || needsInteractive) {
                                    notifyPageFailed(index, e.message)
                                }
                            }
                        } finally {
                            // Only remove *this* job — a replacement may already be registered.
                            downloadJobs.release(index, coroutineContext[Job])
                        }
                    }
                    val ownsSlot = downloadJobs.register(index, job)
                    if (ownsSlot) {
                        job.start()
                    } else {
                        // Lost the race — keep the owner; waiters are already registered.
                        job.cancel()
                    }
                }

                private suspend fun downloadToRam(index: Int) {
                    if (ramPages.containsKey(index)) return
                    val name = imageFileNames[index]
                    val rel = if (remoteDir.isEmpty()) name else "$remoteDir/$name"
                    ZipAsDirListing.zipMemberPath(rel)?.let { (zipRel, member) ->
                        val bytes = ZipMemberCover.extractBytes(
                            "smb:${source.id}:$zipRel",
                            member,
                        ) {
                            SmbArchiveByteSource(
                                source,
                                password,
                                zipRel,
                                pipeline = false,
                                yieldable = false,
                            )
                        } ?: error("Cannot extract ZIP member $member from $zipRel")
                        ramPages[index] = bytes
                        return
                    }
                    val bos = ByteArrayOutputStream()
                    SmbGateway.downloadFile(source, password, rel, bos)
                    ramPages[index] = bos.toByteArray()
                }

                private suspend fun downloadToCache(index: Int) {
                    val name = imageFileNames[index]
                    val cache = SmbCache.cachePath(source.id, remoteDir, name)
                    if (SmbCache.isPageCachedOnDisk(cache)) return
                    val rel = if (remoteDir.isEmpty()) name else "$remoteDir/$name"
                    // Per-path mutex: two connections never write the same cache file.
                    SmbCache.downloadIfNeeded(cache, originalFileName = name) { out ->
                        SmbGateway.downloadFile(source, password, rel, out)
                    }
                }
            },
        )
        block(loader)
    }
}
