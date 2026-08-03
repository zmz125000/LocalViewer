package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveStreamBridge
import com.hippo.ehviewer.library.ArchiveStreamPageCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) = ArchiveAccess.withArchive {
    autoCloseScope {
        coroutineScope {
            ArchiveStreamPageCache.pin(cacheKey)
            install({ }, { _, _ -> ArchiveStreamPageCache.unpin(cacheKey) })

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
                install({ source }, { s, _ -> s.close() })
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
                install({ source }, { s, _ -> s.close() })
                return@coroutineScope block(loader)
            }

            val bridge = install(
                { ArchiveStreamBridge(source) },
                { b, _ -> b.close() },
            )
            // Prefer disk seek index (offsets) so ZIP/TAR reopen skips EOCD/CD / header walk.
            val diskIndex = ArchiveStreamPageCache.loadIndex(cacheKey)
                ?.takeIf {
                    it.remoteSize <= 0L || it.remoteSize == archiveSizeBytes
                }
                ?.takeIf { it.hasFullSeekIndex() }
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
                    val n = openArchiveStream(
                        bridge,
                        archiveSizeBytes,
                        /* sortEntries = */ true,
                        /* coverOnly = */ false,
                        /* progressiveTar = */ true,
                    )
                    check(n > 0) { "Archive have no content!" }
                    n
                },
                { _, _ -> closeArchive() },
            )
            if (needPassword() && archivePasswds.none(::providePassword)) {
                archivePasswds += passwdProvider(::providePassword)
            }
            runCatching {
                ArchiveCoverCache.writeCoverFromOpenArchive(cacheKey, 0L, archiveSizeBytes)
            }.onFailure { logcat(it) }

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
                    listedCount = continueStreamTarIndex(16).coerceAtLeast(listedCount)
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
                        members = rebuilt.toList(),
                    ),
                )
            }
            persistMembers(false)

            // Single-flight native extract (shared stream position / buffer).
            val extractMutex = Mutex()
            val extractJobs = ConcurrentHashMap<Int, Job>()
            val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
            val pagePaths = ConcurrentHashMap<Int, Path>()
            // Pages within this distance of the target keep running; farther jobs cancel.
            val keepWindow = 4
            val hostScope = this
            val tarIndexJob = AtomicReference<Job?>(null)

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
                                            val n = continueStreamTarIndex(12)
                                            if (n > size) {
                                                streamMembersRef.set(
                                                    buildStreamMembers(n, prior = null),
                                                )
                                                growTo(n)
                                                // Persist offsets periodically so kill/resume skips re-walk.
                                                if (n % 24 == 0 || isStreamIndexComplete()) {
                                                    persistMembers(isStreamIndexComplete())
                                                }
                                            } else if (isStreamIndexComplete()) {
                                                streamMembersRef.set(
                                                    buildStreamMembers(
                                                        size.coerceAtLeast(n),
                                                        prior = null,
                                                    ),
                                                )
                                                persistMembers(true)
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
                        pages.forEach { index ->
                            ensureExtract(index, interactive = false)
                        }
                    }

                    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                        cancelDistantExtracts(index)
                        ensureExtract(index, interactive = true) {
                            notifySourceReady(index, orgImg)
                        }
                    }

                    override fun close() {
                        // Drop in-flight extracts so ArchiveAccess can hand off (exit / prev-next).
                        // Snapshot: cancel handlers remove from extractJobs concurrently
                        // (live CHM.values iter on main → NoSuchElementException).
                        tarIndexJob.getAndSet(null)?.cancel()
                        extractJobs.values.toList().forEach { it.cancel() }
                        extractJobs.clear()
                        readyWaiters.clear()
                        // Prefer pagePaths (no disk). Fall back to readdir count so a session
                        // that only touched the last missing pages still flips complete.
                        // Use live [size] (TAR may have grown past initial listedCount).
                        val n = size
                        val members = streamMembersRef.get()
                        val complete = n > 0 &&
                            isStreamIndexComplete() &&
                            (
                                (0 until n).all { pagePaths.containsKey(it) } ||
                                    ArchiveStreamPageCache.countPageFiles(cacheKey) >= n
                                )
                        ArchiveStreamPageCache.saveIndexAsync(
                            ArchiveStreamPageCache.Index(
                                v = ArchiveStreamPageCache.INDEX_VERSION,
                                cacheKey = cacheKey,
                                remoteSize = archiveSizeBytes,
                                format = format,
                                complete = complete,
                                members = members.toList(),
                            ),
                        )
                        // Unblock any JNI read waiting on the network source.
                        runCatching { source.close() }
                        super.close()
                    }


                    private fun cancelDistantExtracts(center: Int) {
                        for ((idx, job) in extractJobs.entries.toList()) {
                            if (kotlin.math.abs(idx - center) > keepWindow) {
                                job.cancel()
                            }
                        }
                    }

                    private fun addReadyWaiter(index: Int, onReady: () -> Unit) {
                        readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                    }

                    private fun takeReadyWaiters(index: Int): List<() -> Unit> =
                        readyWaiters.remove(index)?.toList().orEmpty()

                    private fun dispatchReady(index: Int) {
                        takeReadyWaiters(index).forEach { runCatching { it() } }
                    }

                    private fun isPageCached(index: Int): Boolean {
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
                        val existing = extractJobs[index]
                        if (existing != null && existing.isActive) {
                            return
                        }
                        val job = scope.launch(Dispatchers.IO) {
                            try {
                                if (isPageCached(index)) {
                                    dispatchReady(index)
                                    return@launch
                                }
                                val path = extractToCache(index)
                                if (path != null && ArchiveStreamPageCache.isCached(path)) {
                                    dispatchReady(index)
                                } else {
                                    val waiters = takeReadyWaiters(index)
                                    if (waiters.isNotEmpty()) {
                                        notifyPageFailed(index, "Extract incomplete")
                                    }
                                }
                            } catch (e: CancellationException) {
                                // Re-queue only for in-session job races. On reader exit /
                                // ArchiveAccess preempt, scope is cancelled — do not restart.
                                val owns = extractJobs[index] == coroutineContext[Job]
                                if (scope.isActive && owns) {
                                    val waiters = takeReadyWaiters(index)
                                    if (waiters.isNotEmpty()) {
                                        waiters.forEach { addReadyWaiter(index, it) }
                                        scope.launch(Dispatchers.IO) {
                                            ensureExtract(index, interactive = true)
                                        }
                                    }
                                }
                                throw e
                            } catch (e: Throwable) {
                                logcat(e)
                                val waiters = takeReadyWaiters(index)
                                if (waiters.isNotEmpty() || interactive) {
                                    notifyPageFailed(index, e.message)
                                }
                            } finally {
                                extractJobs.remove(index, coroutineContext[Job])
                            }
                        }
                        val prev = extractJobs.putIfAbsent(index, job)
                        if (prev != null) {
                            if (prev.isActive) {
                                job.cancel()
                            } else {
                                extractJobs[index] = job
                            }
                        }
                    }

                    private fun markCompleteIfReady() {
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
                                members = streamMembersRef.get().toList(),
                            ),
                        )
                    }

                    /** Single-flight extract → page image cache. */
                    private suspend fun extractToCache(index: Int): Path? {
                        ensureActive()
                        if (isPageCached(index)) {
                            val hit = pagePaths[index]
                                ?: ArchiveStreamPageCache.pagePath(cacheKey, index, getExtension(index))
                            if (hit != null) {
                                pagePaths[index] = hit
                                markCompleteIfReady()
                            }
                            return hit
                        }
                        return extractMutex.withLock {
                            ensureActive()
                            if (isPageCached(index)) {
                                val hit = pagePaths[index]
                                    ?: ArchiveStreamPageCache.pagePath(cacheKey, index, getExtension(index))
                                if (hit != null) {
                                    pagePaths[index] = hit
                                    markCompleteIfReady()
                                }
                                return@withLock hit
                            }
                            val ext = getExtension(index).ifBlank { return@withLock null }
                            val buffer = extractToByteBuffer(index) ?: return@withLock null
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
                tarIndexJob.getAndSet(null)?.cancel()
                extractJobs.values.toList().forEach { it.cancel() }
                extractJobs.clear()
                runCatching { source.close() }
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
