package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.continueStreamTarIndex
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.getStreamMemberLength
import com.hippo.ehviewer.jni.getStreamMemberMethod
import com.hippo.ehviewer.jni.getStreamMemberOffset
import com.hippo.ehviewer.jni.getStreamMemberUncSize
import com.hippo.ehviewer.jni.isStreamIndexComplete
import com.hippo.ehviewer.jni.isStreamTarIndex
import com.hippo.ehviewer.jni.loadStreamIndex
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchiveStream
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveStreamBridge
import com.hippo.ehviewer.library.ArchiveStreamPageCache
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.tarsin.kt.install
import okio.Path

/**
 * Stream-open a remote archive via [ArchiveByteSource] + libarchive seek/read.
 *
 * Local folder archives use mmap ([useArchivePageLoader]) and do **not** go through here.
 * SMB/WebDAV non-solid archives: range reads + **extracted page image cache** under
 * [ArchiveStreamPageCache] (the archive file itself is never fully downloaded).
 *
 * Prefetch / seek shape mirrors [useSmbFolderPageLoader]:
 * - Extract jobs run on [Dispatchers.IO] (never block the UI `request()` path)
 * - Native stream I/O is single-flight ([extractMutex]); jobs queue, not parallel-extract
 * - Interactive page (user seek) cancels far-away prefetch jobs so it reaches the mutex sooner
 * - UI waiters are registered so cancel/join races never leave a forever-spinner
 *
 * **TAR progressive index:** cold open lists the first image then continues header walk in
 * the background; [PageLoader.growTo] extends the seek bar as more members appear.
 * Jump only lands on already-listed pages (same constraint as solid lazy list).
 * ZIP still opens via full EOCD+CD (count known immediately).
 */
suspend inline fun <T> useStreamArchivePageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline passwdProvider: PasswdProvider,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        ArchiveStreamPageCache.pin(cacheKey)
        install({ }, { _, _ -> ArchiveStreamPageCache.unpin(cacheKey) })
        // Own the transport from entry, including stat/cache validation failures.
        install({ source }, { s, _ -> s.close() })

        // Offline-first: fully cached ZIP/TAR must not wait on remote size/stat
        // (SMB/WebDAV HEAD) or re-run EOCD/TAR header index.
        val offlineReady = ArchiveStreamPageCache.isCompleteAndReady(cacheKey, remoteSize = 0L)
        if (offlineReady != null) {
            ArchiveStreamPageCache.touchAsync(cacheKey)
            val loader = install(
                cachedStreamLoader(
                    scope = this,
                    cacheKey = cacheKey,
                    streamIndex = offlineReady,
                    titleHint = titleHint,
                    info = info,
                    startPage = startPage,
                    hasAds = hasAds,
                ),
            )
            return@coroutineScope block(loader)
        }

        // Soft-fail remote size (WebDAV restart): IOException → open fails cleanly, no process crash.
        val archiveSizeBytes = runCatching { source.size }.getOrDefault(-1L)
        check(archiveSizeBytes > 0L) {
            "Cannot open stream archive (size unknown): $cacheKey"
        }
        ArchiveStreamPageCache.invalidateIfRemoteSizeMismatch(cacheKey, archiveSizeBytes)
        // Re-check after size match (index may have been purged on mismatch).
        val ready = ArchiveStreamPageCache.isCompleteAndReady(cacheKey, remoteSize = archiveSizeBytes)
        if (ready != null) {
            ArchiveStreamPageCache.touchAsync(cacheKey)
            val loader = install(
                cachedStreamLoader(
                    scope = this,
                    cacheKey = cacheKey,
                    streamIndex = ready,
                    titleHint = titleHint,
                    info = info,
                    startPage = startPage,
                    hasAds = hasAds,
                ),
            )
            return@coroutineScope block(loader)
        }

        // Cache-only readers never touch process-global libarchive state. Acquire the
        // archive lease only after both offline checks miss.
        return@coroutineScope ArchiveAccess.withArchive {
            val bridge = install(
                { ArchiveStreamBridge(source) },
                { b, _ -> b.close() },
            )
            // Prefer disk seek index (offsets) so ZIP/TAR reopen skips EOCD/CD / header walk.
            val diskIndex = ArchiveStreamPageCache.loadIndex(cacheKey)
                ?.takeIf {
                    it.remoteSize <= 0L || it.remoteSize == archiveSizeBytes
                }
                // TAR: require structureComplete so partial progressive walks cannot freeze
                // page count. ZIP CD is always a full member list when offsets are present.
                ?.takeIf { it.canOpenFromSeekIndexOnly() }
            val openedFromDisk = AtomicInteger(0)
            val pageCount = install(
                {
                    val fromDisk = diskIndex?.let { idx ->
                        openFromSeekIndex(bridge, archiveSizeBytes, idx)
                    } ?: 0
                    if (fromDisk > 0) {
                        openedFromDisk.set(1)
                        return@install fromDisk
                    }
                    // progressiveTar=true: TAR first image only; ZIP still full CD.
                    // checkedNative surfaces RemoteRangeNotSupportedException cleared by C.
                    val n = bridge.checkedNative {
                        openArchiveStream(
                            bridge,
                            archiveSizeBytes,
                            /* sortEntries = */
                            true,
                            /* coverOnly = */
                            false,
                            /* progressiveTar = */
                            true,
                            /* maxScanBytes = */
                            0L,
                        )
                    }
                    check(n > 0) { "Archive have no content!" }
                    n
                },
                { _, _ -> closeArchive() },
            )
            if (needPassword() && archivePasswds.none(::providePassword)) {
                archivePasswds += passwdProvider(::providePassword)
            }

            val format = when (val fmt = diskIndex?.format) {
                "zip", "tar" -> fmt
                else -> if (isStreamTarIndex()) "tar" else "zip"
            }
            // Progressive TAR: optionally advance walk until resume page is listed.
            var listedCount = pageCount
            if (format == "tar" &&
                openedFromDisk.get() == 0 &&
                !isStreamIndexComplete() &&
                startPage > 0
            ) {
                while (listedCount <= startPage && !isStreamIndexComplete()) {
                    listedCount = bridge.checkedNative {
                        continueStreamTarIndex(16)
                    }.coerceAtLeast(listedCount)
                }
            }
            val streamMembersRef = AtomicReference(
                buildStreamMembers(listedCount, prior = diskIndex),
            )
            // Persist seek offsets (no local fun — not allowed inside inline).
            val persistMembers: (Boolean) -> Unit = persist@{ _completeHint ->
                val members = streamMembersRef.get()
                val rebuilt = if (format == "tar" && isStreamIndexComplete()) {
                    buildStreamMembers(members.size.coerceAtLeast(1), prior = null)
                } else {
                    members
                }
                streamMembersRef.set(rebuilt)
                ArchiveStreamPageCache.saveIndexAsync(
                    ArchiveStreamPageCache.Index(
                        v = ArchiveStreamPageCache.INDEX_VERSION,
                        cacheKey = cacheKey,
                        remoteSize = archiveSizeBytes,
                        format = format,
                        complete = false, // page completeness tracked separately
                        // ZIP CD is full; TAR only when progressive walk finished.
                        structureComplete = format == "zip" || isStreamIndexComplete(),
                        members = rebuilt.toList(),
                    ),
                )
            }
            persistMembers(false)

            // Single-flight native extract (shared stream position / buffer).
            val extractMutex = Mutex()
            val extractJobs = KeyedJobRegistry<Int>()
            val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
            val pagePaths = ConcurrentHashMap<Int, Path>()
            val ramPages = ConcurrentHashMap<Int, ByteArray>()
            val hostScope = this
            val tarIndexJob = AtomicReference<Job?>(null)
            val coverScheduled = AtomicBoolean(false)

            val loader = install(
                object : PageLoader(
                    this,
                    info,
                    startPage.coerceIn(0, (listedCount - 1).coerceAtLeast(0)),
                    listedCount,
                    hasAds,
                ) {
                    override val title by lazy { info?.title ?: titleHint }

                    init {
                        // TAR: keep walking headers so seek bar grows; ZIP already complete.
                        if (format == "tar" &&
                            openedFromDisk.get() == 0 &&
                            !isStreamIndexComplete()
                        ) {
                            tarIndexJob.set(
                                hostScope.launch(Dispatchers.IO) {
                                    try {
                                        while (isActive && !isStreamIndexComplete()) {
                                            val before = size
                                            val n = bridge.checkedNative {
                                                continueStreamTarIndex(12)
                                            }
                                            if (n > size) {
                                                streamMembersRef.set(
                                                    buildStreamMembers(n, prior = null),
                                                )
                                                growTo(n)
                                                // Persist offsets periodically so kill/resume skips re-walk.
                                                if (n % 24 == 0 || isStreamIndexComplete()) {
                                                    if (!Settings.disableReaderNetworkCache.value) {
                                                        persistMembers(isStreamIndexComplete())
                                                    }
                                                }
                                            } else if (isStreamIndexComplete()) {
                                                streamMembersRef.set(
                                                    buildStreamMembers(
                                                        size.coerceAtLeast(n),
                                                        prior = null,
                                                    ),
                                                )
                                                if (!Settings.disableReaderNetworkCache.value) {
                                                    persistMembers(true)
                                                }
                                                break
                                            } else if (n <= before) {
                                                // Native walk stopped incomplete (I/O/corruption/abort).
                                                // Do not spin forever; keep structureComplete=false so
                                                // a later open retries discovery.
                                                logcat("StreamTarIndex") {
                                                    "TAR index stopped without progress key=$cacheKey count=$n"
                                                }
                                                break
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        logcat("StreamTarIndex", e)
                                    }
                                },
                            )
                        }
                    }

                    override fun getImageExtension(index: Int) = getExtension(index)

                    override fun save(index: Int, file: Path): Boolean = runCatching {
                        ramPages[index]?.let {
                            java.io.File(file.toString()).writeBytes(it)
                            return@runCatching true
                        }
                        val ext = getExtension(index).ifBlank { return@runCatching false }
                        val path = pagePaths[index]
                            ?.takeIf { ArchiveStreamPageCache.isCached(it) }
                            ?: ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                                .takeIf { ArchiveStreamPageCache.isCached(it) }
                            ?: error("Not cached")
                        java.io.File(path.toString()).copyTo(java.io.File(file.toString()), overwrite = true)
                        true
                    }.getOrDefault(false)

                    override fun openSource(index: Int): ImageSource {
                        ramPages[index]?.let { bytes ->
                            return byteBufferSource(ByteBuffer.wrap(bytes)) {}
                        }
                        val ext = getExtension(index)
                        val path = pagePaths[index]
                            ?.takeIf { ArchiveStreamPageCache.isCached(it) }
                            ?: ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                                .takeIf { ArchiveStreamPageCache.isCached(it) }
                        checkNotNull(path) { "Stream archive page $index not extracted" }
                        pagePaths[index] = path
                        return object : PathSource {
                            override val source: Path = path
                            override val type = ext
                            override fun close() = Unit
                        }
                    }

                    override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                        if (Settings.disableReaderNetworkCache.value) return
                        pages.forEach { index ->
                            ensureExtract(index, interactive = false)
                        }
                    }

                    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                        ensureExtract(index, interactive = true) {
                            notifySourceReady(index, orgImg)
                            // Any interactive page: reuse cached page 0 for cover if present.
                            // Helper never performs native extract when page 0 is missing.
                            scheduleStreamArchiveCoverFromPage0(
                                coverScheduled = coverScheduled,
                                cacheKey = cacheKey,
                                pagePaths = pagePaths,
                                page0Ext = streamMembersRef.get()
                                    .firstOrNull { it.i == 0 }
                                    ?.ext,
                            )
                        }
                    }

                    override fun onNavigation(demand: ReaderDemand) {
                        cancelStaleExtracts(demand.sourcePages, demand.decodedPages)
                    }

                    override fun close() {
                        // Drop in-flight extracts so ArchiveAccess can hand off (exit / prev-next).
                        // Snapshot: cancel handlers remove from extractJobs concurrently
                        // (live CHM.values iter on main → NoSuchElementException).
                        tarIndexJob.getAndSet(null)?.cancel()
                        extractJobs.cancelAll()
                        readyWaiters.clear()
                        // Prefer pagePaths (memory only — close runs on main via Compose dispose).
                        // Disk readdir for "all pages present" is deferred to IO so StrictMode
                        // does not fire on File.list during onDispose.
                        val n = size
                        val members = streamMembersRef.get().toList()
                        val indexDone = isStreamIndexComplete()
                        val memoryComplete = n > 0 &&
                            indexDone &&
                            (0 until n).all { pagePaths.containsKey(it) }
                        if (!Settings.disableReaderNetworkCache.value) {
                            ArchiveStreamPageCache.saveIndexOnCloseAsync(
                                index = ArchiveStreamPageCache.Index(
                                    v = ArchiveStreamPageCache.INDEX_VERSION,
                                    cacheKey = cacheKey,
                                    remoteSize = archiveSizeBytes,
                                    format = format,
                                    complete = memoryComplete,
                                    structureComplete = format == "zip" || indexDone,
                                    members = members,
                                ),
                                memoryComplete = memoryComplete,
                                probeDiskForComplete = n > 0 && indexDone && !memoryComplete,
                                expectedPageCount = n,
                            )
                        }
                        // Unblock any JNI read waiting on the network source.
                        runCatching { source.close() }
                        super.close()
                    }

                    private fun cancelStaleExtracts(sourcePages: Set<Int>, decodedPages: Set<Int>) {
                        readyWaiters.forEach { idx, _ -> if (idx !in decodedPages) readyWaiters.remove(idx) }
                        ramPages.keys.toList().forEach { idx ->
                            if (idx !in decodedPages) ramPages.remove(idx)
                        }
                        // ConcurrentHashMap.forEach — avoid entries.toList() iterator race on Android.
                        extractJobs.cancelOutside(sourcePages)
                    }

                    private fun addReadyWaiter(index: Int, onReady: () -> Unit) {
                        readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                    }

                    private fun takeReadyWaiters(index: Int): List<() -> Unit> = readyWaiters.remove(index)?.toList().orEmpty()

                    private fun dispatchReady(index: Int) {
                        takeReadyWaiters(index).forEach { runCatching { it() } }
                    }

                    private fun isPageCached(index: Int): Boolean {
                        if (ramPages.containsKey(index)) return true
                        pagePaths[index]?.let { if (ArchiveStreamPageCache.isCached(it)) return true }
                        val ext = getExtension(index).ifBlank { return false }
                        val path = ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                        if (ArchiveStreamPageCache.isCached(path)) {
                            pagePaths[index] = path
                            return true
                        }
                        return false
                    }

                    /**
                     * Start or join an extract for [index].
                     * Disk probes run only on [Dispatchers.IO] — onRequest/retryPage are main-thread.
                     * - In-flight job → only register waiter
                     * - Else launch IO job; extracts take [extractMutex] one at a time
                     */
                    private fun ensureExtract(
                        index: Int,
                        interactive: Boolean,
                        onReady: (() -> Unit)? = null,
                    ) {
                        if (index !in 0 until size) return
                        if (onReady != null) {
                            addReadyWaiter(index, onReady)
                        }
                        val existing = extractJobs.owner(index)
                        if (existing != null && !existing.isCompleted) {
                            return
                        }
                        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            try {
                                if (isPageCached(index)) {
                                    dispatchReady(index)
                                    return@launch
                                }
                                val skipDisk = Settings.disableReaderNetworkCache.value
                                if (skipDisk) {
                                    extractToRam(index)
                                    if (ramPages.containsKey(index) || isPageCached(index)) {
                                        dispatchReady(index)
                                    } else {
                                        val waiters = takeReadyWaiters(index)
                                        if (waiters.isNotEmpty()) {
                                            notifyPageFailed(index, "Extract incomplete")
                                        }
                                    }
                                } else {
                                    val path = extractToCache(index)
                                    if (path != null && ArchiveStreamPageCache.isCached(path)) {
                                        dispatchReady(index)
                                    } else {
                                        val waiters = takeReadyWaiters(index)
                                        if (waiters.isNotEmpty()) {
                                            notifyPageFailed(index, "Extract incomplete")
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                // Re-queue only for in-session job races. On reader exit /
                                // ArchiveAccess preempt, scope is cancelled — do not restart.
                                val runningJob = coroutineContext[Job]
                                val owns = extractJobs.owns(index, runningJob)
                                if (scope.isActive && owns) {
                                    val waiters = takeReadyWaiters(index)
                                    if (waiters.isNotEmpty()) {
                                        waiters.forEach { addReadyWaiter(index, it) }
                                        extractJobs.release(index, runningJob)
                                        ensureExtract(index, interactive = true)
                                    }
                                }
                                throw e
                            } catch (e: Throwable) {
                                if (extractJobs.owns(index, coroutineContext[Job])) {
                                    logcat(e)
                                    val waiters = takeReadyWaiters(index)
                                    if (waiters.isNotEmpty() || interactive) {
                                        notifyPageFailed(index, e.message)
                                    }
                                }
                            } finally {
                                extractJobs.release(index, coroutineContext[Job])
                            }
                        }
                        val ownsSlot = extractJobs.register(index, job)
                        if (ownsSlot) job.start() else job.cancel()
                    }

                    private fun markCompleteIfReady() {
                        if (Settings.disableReaderNetworkCache.value) return
                        // Hot path: only session map (no readdir). Disk-complete repair is
                        // handled on next open via [ArchiveStreamPageCache.isCompleteAndReady].
                        // TAR progressive: only flip complete when index walk finished + all pages.
                        val n = size
                        if (n <= 0 || pagePaths.size < n || !isStreamIndexComplete()) return
                        ArchiveStreamPageCache.saveIndexAsync(
                            ArchiveStreamPageCache.Index(
                                v = ArchiveStreamPageCache.INDEX_VERSION,
                                cacheKey = cacheKey,
                                remoteSize = archiveSizeBytes,
                                format = format,
                                complete = true,
                                structureComplete = true,
                                members = streamMembersRef.get().toList(),
                            ),
                        )
                    }

                    /** Extract to RAM when reader network cache is disabled. */
                    private suspend fun extractToRam(index: Int) {
                        if (ramPages.containsKey(index) || isPageCached(index)) return
                        extractMutex.withLock {
                            if (ramPages.containsKey(index) || isPageCached(index)) return@withLock
                            val buffer = bridge.checkedNative {
                                extractToByteBuffer(index)
                            } ?: return@withLock
                            try {
                                ensureActive()
                                check(buffer.isDirect)
                                val bytes = ByteArray(buffer.remaining())
                                buffer.duplicate().get(bytes)
                                ramPages[index] = bytes
                            } finally {
                                releaseByteBuffer(buffer)
                            }
                        }
                    }

                    /** Single-flight extract → page image cache. */
                    private suspend fun extractToCache(index: Int): Path? {
                        fun cachedPath(): Path {
                            val hit = pagePaths[index]
                                ?: ArchiveStreamPageCache.pagePath(cacheKey, index, getExtension(index))
                            pagePaths[index] = hit
                            markCompleteIfReady()
                            return hit
                        }
                        ensureActive()
                        if (isPageCached(index)) return cachedPath()
                        return extractMutex.withLock {
                            ensureActive()
                            if (isPageCached(index)) return@withLock cachedPath()
                            val ext = getExtension(index).ifBlank { return@withLock null }
                            val buffer = bridge.checkedNative {
                                extractToByteBuffer(index)
                            } ?: return@withLock null
                            try {
                                // Reader exit may cancel while native extract was finishing —
                                // do not publish a buffer we no longer own the session for.
                                ensureActive()
                                check(buffer.isDirect)
                                val written = ArchiveStreamPageCache.writePage(cacheKey, index, ext, buffer)
                                pagePaths[index] = written
                                // Promote to offline-capable as soon as every page is mapped
                                // (don't wait for close — close often only saw a subset in pagePaths).
                                markCompleteIfReady()
                                // No warm(next): readahead extend-tail + pipeline continue the
                                // sequential high-water without re-GETting overlapping ranges.
                                written
                            } finally {
                                releaseByteBuffer(buffer)
                            }
                        }
                    }
                },
            )
            try {
                block(loader)
            } finally {
                // AutoCloseScope calls closeArchive after this block. Cancellation alone is
                // not enough: a JNI extract may still be unwinding on Dispatchers.IO.
                val indexJob = tarIndexJob.getAndSet(null)
                val jobs = extractJobs.cancelAll()
                indexJob?.cancel()
                // Unblock SMB/WebDAV reads before waiting, even when this scope was preempted.
                runCatching { source.close() }
                withContext(NonCancellable) {
                    (jobs + listOfNotNull(indexJob)).joinAll()
                }
            }
        }
    }
}

/**
 * Open stream session from disk seek index (no ZIP CD / TAR header network walk).
 * @return page count or 0 if load failed (caller falls back to [openArchiveStream]).
 */
@PublishedApi
internal fun openFromSeekIndex(
    bridge: ArchiveStreamBridge,
    archiveSizeBytes: Long,
    idx: ArchiveStreamPageCache.Index,
): Int {
    val members = idx.members.sortedBy { it.i }
    if (members.isEmpty() || !members.all { it.hasSeek }) return 0
    val n = members.size
    val offsets = LongArray(n) { members[it].offset }
    val unc = LongArray(n) { members[it].uncSize }
    val comp = LongArray(n) {
        val c = members[it].compSize
        if (c > 0L) c else members[it].uncSize
    }
    val methods = IntArray(n) {
        val m = members[it].method
        if (m >= 0) m else 0
    }
    val names = Array(n) { i ->
        val ext = members[i].ext.ifBlank { "bin" }
        members[i].name.ifBlank { "%06d.%s".format(members[i].i, ext) }
    }
    // Only trust explicit format — ZIP store also uses method 0.
    val isTar = idx.format == "tar"
    return runCatching {
        bridge.checkedNative {
            loadStreamIndex(
                bridge,
                archiveSizeBytes,
                offsets,
                unc,
                comp,
                methods,
                names,
                isTar,
            )
        }
    }.getOrDefault(0)
}

/** Build member list with seek offsets from live JNI (or reuse prior disk values). */
@PublishedApi
internal fun buildStreamMembers(
    pageCount: Int,
    prior: ArchiveStreamPageCache.Index?,
): ArrayList<ArchiveStreamPageCache.Member> {
    val priorByI = prior?.members?.associateBy { it.i }.orEmpty()
    val out = ArrayList<ArchiveStreamPageCache.Member>(pageCount)
    for (i in 0 until pageCount) {
        val ext = getExtension(i).ifBlank { null } ?: priorByI[i]?.ext ?: "bin"
        val off = getStreamMemberOffset(i).takeIf { it >= 0L } ?: priorByI[i]?.offset ?: -1L
        val comp = getStreamMemberLength(i).takeIf { it >= 0L } ?: priorByI[i]?.compSize ?: -1L
        val unc = getStreamMemberUncSize(i).takeIf { it > 0L } ?: priorByI[i]?.uncSize ?: 0L
        val method = getStreamMemberMethod(i).takeIf { it >= 0 } ?: priorByI[i]?.method ?: -1
        out += ArchiveStreamPageCache.Member(
            i = i,
            name = priorByI[i]?.name.orEmpty(),
            ext = ext,
            uncSize = unc,
            offset = off,
            compSize = comp,
            method = method,
        )
    }
    return out
}

/**
 * Encode cover from reader-published page 0 only (session map or stream page cache).
 * Never acquires [extractMutex] / native extract — covers must not block interactive pages.
 * Remote keys use mtime/size 0 (shared with browse).
 */
fun scheduleStreamArchiveCoverFromPage0(
    coverScheduled: AtomicBoolean,
    cacheKey: String,
    pagePaths: ConcurrentHashMap<Int, Path>,
    page0Ext: String?,
) {
    if (!coverScheduled.compareAndSet(false, true)) return
    val cachedPath = pagePaths[0]
        ?: page0Ext?.ifBlank { null }?.let {
            ArchiveStreamPageCache.pagePath(cacheKey, 0, it)
        }
    if (cachedPath == null) {
        // Reader has not published/listed page 0 yet — do not extract solely for cover.
        coverScheduled.set(false)
        return
    }
    ArchiveCoverCache.scheduleEncodeFromExtractedPage(cacheKey, cachedPath) { cover ->
        // Missing page or failed encode: permit a later request to retry.
        // Success remains single-shot for this reader session.
        if (cover == null) coverScheduled.set(false)
    }
}

@PublishedApi
internal fun cachedStreamLoader(
    scope: CoroutineScope,
    cacheKey: String,
    streamIndex: ArchiveStreamPageCache.Index,
    titleHint: String,
    info: GalleryInfo?,
    startPage: Int,
    hasAds: Boolean,
): PageLoader {
    val pageCount = streamIndex.members.size
    val exts = streamIndex.members.associate { it.i to it.ext }
    // Offline complete path never hits onRequest extract — regenerate cover from page 0 file.
    val page0 = streamIndex.members.firstOrNull { it.i == 0 }?.let {
        ArchiveStreamPageCache.pagePath(cacheKey, 0, it.ext)
    }
    if (
        page0 != null &&
        ArchiveStreamPageCache.isCached(page0) &&
        !ArchiveCoverCache.isCoverCached(cacheKey)
    ) {
        ArchiveCoverCache.scheduleEncodeFromExtractedPage(cacheKey, page0)
    }
    return object : PageLoader(
        scope,
        info,
        startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount,
        hasAds,
    ) {
        override val title by lazy { info?.title ?: titleHint }

        override fun getImageExtension(index: Int) = exts[index]

        override fun save(index: Int, file: Path): Boolean = runCatching {
            val ext = exts[index] ?: return@runCatching false
            val path = ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
            java.io.File(path.toString()).copyTo(java.io.File(file.toString()), overwrite = true)
            true
        }.getOrDefault(false)

        override fun openSource(index: Int): ImageSource {
            // Complete index already verified — no open-time File.stat of every page.
            val ext = exts[index] ?: "bin"
            val path = ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
            return object : PathSource {
                override val source: Path = path
                override val type: String = ext
                override fun close() = Unit
            }
        }

        override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

        override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
            notifySourceReady(index, orgImg)
        }
    }
}
