package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.getStreamMemberLength
import com.hippo.ehviewer.jni.getStreamMemberOffset
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchiveStream
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveStreamBridge
import com.hippo.ehviewer.library.ArchiveStreamPageCache
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
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
            // Soft-fail remote size (WebDAV restart): IOException → open fails cleanly, no process crash.
            val archiveSizeBytes = runCatching { source.size }.getOrDefault(-1L)
            check(archiveSizeBytes > 0L) {
                "Cannot open stream archive (size unknown): $cacheKey"
            }
            ArchiveStreamPageCache.invalidateIfRemoteSizeMismatch(cacheKey, archiveSizeBytes)
            ArchiveStreamPageCache.pin(cacheKey)
            install({ }, { _, _ -> ArchiveStreamPageCache.unpin(cacheKey) })

            // Full offline: all pages on disk + index → skip libarchive / network open.
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
            val pageCount = install(
                {
                    val n = openArchiveStream(bridge, archiveSizeBytes, true, false)
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

            // Member list for offline reopen — build + write async so open is not blocked
            // by N getExtension + index.json write (was making "resume" feel slower than cold).
            val streamMembers = ArrayList<ArchiveStreamPageCache.Member>(pageCount)
            for (i in 0 until pageCount) {
                val ext = getExtension(i)?.ifBlank { null } ?: "bin"
                streamMembers += ArchiveStreamPageCache.Member(i = i, name = "", ext = ext, uncSize = 0L)
            }
            ArchiveStreamPageCache.saveIndexAsync(
                ArchiveStreamPageCache.Index(
                    cacheKey = cacheKey,
                    remoteSize = archiveSizeBytes,
                    format = "stream",
                    complete = false,
                    members = streamMembers,
                ),
            )

            // Single-flight native extract (shared stream position / buffer).
            val extractMutex = Mutex()
            val extractJobs = ConcurrentHashMap<Int, Job>()
            val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
            val pagePaths = ConcurrentHashMap<Int, Path>()
            // Pages within this distance of the target keep running; farther jobs cancel.
            val keepWindow = 4

            val loader = install(
                object : PageLoader(
                    this,
                    info,
                    startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                    pageCount,
                    hasAds,
                ) {
                    override val title by lazy { info?.title ?: titleHint }

                    override fun getImageExtension(index: Int) = getExtension(index)

                    override fun save(index: Int, file: Path): Boolean = runCatching {
                        val ext = getExtension(index) ?: return@runCatching false
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
                        extractJobs.values.forEach { it.cancel() }
                        extractJobs.clear()
                        readyWaiters.clear()
                        // Trust in-memory map only — no disk stats on main/onDispose.
                        val complete = pageCount > 0 &&
                            (0 until pageCount).all { pagePaths.containsKey(it) }
                        ArchiveStreamPageCache.saveIndexAsync(
                            ArchiveStreamPageCache.Index(
                                cacheKey = cacheKey,
                                remoteSize = archiveSizeBytes,
                                format = "stream",
                                complete = complete,
                                members = streamMembers,
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
                        val ext = getExtension(index) ?: return false
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

                    /** Single-flight extract → page image cache. */
                    private suspend fun extractToCache(index: Int): Path? {
                        ensureActive()
                        if (isPageCached(index)) {
                            return pagePaths[index]
                                ?: getExtension(index)?.let { ArchiveStreamPageCache.pagePath(cacheKey, index, it) }
                        }
                        return extractMutex.withLock {
                            ensureActive()
                            if (isPageCached(index)) {
                                return@withLock pagePaths[index]
                                    ?: getExtension(index)?.let { ArchiveStreamPageCache.pagePath(cacheKey, index, it) }
                            }
                            val ext = getExtension(index) ?: return@withLock null
                            val buffer = extractToByteBuffer(index) ?: return@withLock null
                            try {
                                check(buffer.isDirect)
                                val written = ArchiveStreamPageCache.writePage(cacheKey, index, ext, buffer)
                                pagePaths[index] = written
                                // Promote to offline-capable as soon as every page is mapped
                                // (don't wait for close — close often only saw a subset in pagePaths).
                                if (pagePaths.size >= pageCount) {
                                    ArchiveStreamPageCache.saveIndexAsync(
                                        ArchiveStreamPageCache.Index(
                                            cacheKey = cacheKey,
                                            remoteSize = archiveSizeBytes,
                                            format = "stream",
                                            complete = true,
                                            members = streamMembers,
                                        ),
                                    )
                                }
                                // While still on the archive IO path, warm next page so sequential
                                // flip/prefetch hits readahead instead of a cold Range/SMB seek.
                                warmNextPage(index + 1)
                                written
                            } finally {
                                releaseByteBuffer(buffer)
                            }
                        }
                    }

                    /**
                     * Prefill readahead for the next member (ZIP local header / TAR data).
                     * Caps at [ReadAheadArchiveByteSource.SEQUENTIAL_WINDOW] (8 MiB).
                     */
                    private fun warmNextPage(next: Int) {
                        if (next !in 0 until size) return
                        if (isPageCached(next)) return
                        runCatching {
                            val off = getStreamMemberOffset(next)
                            val len = getStreamMemberLength(next)
                            if (off < 0L || len <= 0L) return
                            // ZIP: offset is local header — include a little header slack.
                            val need = (len + 512L).coerceAtMost(
                                ReadAheadArchiveByteSource.SEQUENTIAL_WINDOW.toLong(),
                            ).toInt()
                            source.warm(off, need)
                        }.onFailure { logcat(it) }
                    }
                },
            )
            try {
                block(loader)
            } finally {
                extractJobs.values.forEach { it.cancel() }
                extractJobs.clear()
                runCatching { source.close() }
            }
        }
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
