package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveStreamPageCache
import com.hippo.ehviewer.library.TarChunkEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.kt.install
import okio.Path

/**
 * TAR/CBT stream reader: fixed readahead windows drive **index + extract together**
 * ([TarChunkEngine]). No separate sparse header walk.
 *
 * Seek bar grows as members are discovered; only listed pages are seekable until EOF.
 */
suspend inline fun <T> useTarChunkPageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline block: suspend (PageLoader) -> T,
): T = autoCloseScope {
    coroutineScope {
        ArchiveStreamPageCache.pin(cacheKey)
        install({ }, { _, _ -> ArchiveStreamPageCache.unpin(cacheKey) })
        install({ source }, { s, _ -> s.close() })

        val offlineReady = ArchiveStreamPageCache.isCompleteAndReady(cacheKey, remoteSize = 0L)
        if (offlineReady != null && offlineReady.format == "tar") {
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

        val archiveSize = runCatching { source.size }.getOrDefault(-1L)
        check(archiveSize > 0L) { "Cannot open TAR (size unknown): $cacheKey" }
        ArchiveStreamPageCache.invalidateIfRemoteSizeMismatch(cacheKey, archiveSize)
        val ready = ArchiveStreamPageCache.isCompleteAndReady(cacheKey, remoteSize = archiveSize)
        if (ready != null && ready.format == "tar") {
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

        val engine = TarChunkEngine(source, cacheKey, archiveSize)
        val diskIndex = ArchiveStreamPageCache.loadIndex(cacheKey)
            ?.takeIf { it.remoteSize <= 0L || it.remoteSize == archiveSize }
        // Half-cache reopen: seed pages/ + index; header-walk skips cached bodies.
        engine.seedFromDisk(diskIndex)

        // Need at least page 0 listed+extracted for open.
        if (!engine.isKnownOnDisk(0)) {
            engine.ensureThrough(0)
        }
        check(engine.listedCount() > 0) { "TAR has no playable images" }

        // Resume: header-walk (skip cached) to startPage, extract only misses.
        if (startPage > 0 && !engine.isKnownOnDisk(startPage)) {
            engine.ensureThrough(startPage)
        }

        ArchiveStreamPageCache.saveIndexAsync(engine.toIndex(completePages = false))

        val pagePaths = ConcurrentHashMap<Int, Path>()
        // Map every cached page (index members and/or readdir) for instant resume.
        for (m in engine.membersSnapshot()) {
            if (engine.isKnownOnDisk(m.i)) {
                pagePaths[m.i] = ArchiveStreamPageCache.pagePath(cacheKey, m.i, m.ext)
            }
        }
        for ((i, ext) in ArchiveStreamPageCache.listCachedPages(cacheKey)) {
            if (!pagePaths.containsKey(i)) {
                pagePaths[i] = ArchiveStreamPageCache.pagePath(cacheKey, i, ext)
            }
        }
        val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
        val extractJobs = ConcurrentHashMap<Int, Job>()
        val extractMutex = Mutex()
        val coverWritten = AtomicBoolean(false)
        val hostScope = this
        val prefetchN = Settings.preloadImage.value.coerceAtLeast(1)
        val extractTarget = AtomicInteger((startPage + prefetchN).coerceAtLeast(0))
        val bgJob = AtomicReference<Job?>(null)

        // Cover encode after loader publish (page 0 may already be on disk from open).
        val page0ForCover = pagePaths[0]
        if (page0ForCover != null && coverWritten.compareAndSet(false, true)) {
            ArchiveCoverCache.scheduleEncodeFromExtractedPage(cacheKey, page0ForCover)
        }

        val loader = install(
            object : PageLoader(
                hostScope,
                info,
                startPage.coerceIn(0, (engine.listedCount() - 1).coerceAtLeast(0)),
                engine.listedCount(),
                hasAds,
            ) {
                override val title by lazy { info?.title ?: titleHint }

                init {
                    val self = this
                    engine.onListed = { count -> self.growTo(count) }
                    engine.onPageReady = pageReady@{ index ->
                        val ext = engine.extOf(index) ?: return@pageReady
                        pagePaths[index] = ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                        self.growTo(engine.listedCount())
                        if (index == 0 && coverWritten.compareAndSet(false, true)) {
                            hostScope.launch(Dispatchers.IO) {
                                runCatching {
                                    ArchiveCoverCache.writeCoverFromExtractedPage(
                                        cacheKey,
                                        pagePaths[0]!!,
                                    )
                                }
                            }
                        }
                        readyWaiters.remove(index)?.forEach { runCatching { it() } }
                    }
                }

                override fun getImageExtension(index: Int) = engine.extOf(index)

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    val ext = engine.extOf(index) ?: return@runCatching false
                    val path = pagePaths[index]
                        ?: ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                            .takeIf { ArchiveStreamPageCache.isCached(it) }
                        ?: error("Not cached")
                    File(path.toString()).copyTo(File(file.toString()), overwrite = true)
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val ext = engine.extOf(index) ?: "bin"
                    val path = pagePaths[index] ?: error("TAR page $index not ready")
                    return object : PathSource {
                        override val source: Path = path
                        override val type: String = ext
                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    pages.forEach { ensureExtract(it) }
                    pages.maxOrNull()?.let { extractTarget.updateAndGet { cur -> maxOf(cur, it) } }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    ensureExtract(index) { notifySourceReady(index, orgImg) }
                }

                override fun onNavigation(demand: ReaderDemand) {
                    val target = demand.progressiveDiscoveryTarget(prefetchN)
                    extractTarget.updateAndGet { cur -> maxOf(cur, target) }
                }

                override fun close() {
                    engine.abort()
                    bgJob.getAndSet(null)?.cancel()
                    extractJobs.values.toList().forEach { it.cancel() }
                    extractJobs.clear()
                    readyWaiters.clear()
                    val n = size
                    val complete = engine.isComplete && n > 0 &&
                        (0 until n).all { pagePaths.containsKey(it) }
                    ArchiveStreamPageCache.saveIndexAsync(
                        engine.toIndex(completePages = complete),
                    )
                    super.close()
                }

                private fun ensureExtract(index: Int, onReady: (() -> Unit)? = null) {
                    if (index < 0) return
                    if (onReady != null) {
                        readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                        if (pagePaths.containsKey(index)) {
                            onReady()
                            return
                        }
                    } else if (pagePaths.containsKey(index)) {
                        return
                    }
                    val existing = extractJobs[index]
                    if (existing != null && existing.isActive) return
                    val job = hostScope.launch(Dispatchers.IO) {
                        try {
                            if (pagePaths.containsKey(index)) {
                                readyWaiters.remove(index)?.forEach { runCatching { it() } }
                                return@launch
                            }
                            extractMutex.withLock {
                                ensureActive()
                                engine.ensureThrough(index)
                            }
                            val ext = engine.extOf(index)
                            if (ext != null && engine.isKnownOnDisk(index)) {
                                pagePaths[index] =
                                    ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                                readyWaiters.remove(index)?.forEach { runCatching { it() } }
                            } else {
                                val waiters = readyWaiters.remove(index).orEmpty()
                                if (waiters.isNotEmpty()) {
                                    notifyPageFailed(index, "Extract incomplete")
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logcat("TarChunk", e)
                            val waiters = readyWaiters.remove(index).orEmpty()
                            if (waiters.isNotEmpty()) notifyPageFailed(index, e.message)
                        } finally {
                            extractJobs.remove(index, coroutineContext[Job])
                        }
                    }
                    val prev = extractJobs.putIfAbsent(index, job)
                    if (prev != null) {
                        if (prev.isActive) job.cancel() else extractJobs[index] = job
                    }
                }
            },
        )

        bgJob.set(
            hostScope.launch(Dispatchers.IO) {
                try {
                    var last = -1
                    while (isActive && !engine.isComplete && !engine.isAborted) {
                        val target = extractTarget.get()
                        if (target != last) {
                            extractMutex.withLock {
                                engine.ensureThroughHighWater(target)
                            }
                            loader.growTo(engine.listedCount())
                            last = target
                            if (engine.listedCount() % 8 == 0 || engine.isComplete) {
                                ArchiveStreamPageCache.saveIndexAsync(
                                    engine.toIndex(completePages = false),
                                )
                            }
                        } else {
                            delay(50)
                        }
                    }
                    if (engine.isComplete) {
                        ArchiveStreamPageCache.saveIndexAsync(
                            engine.toIndex(
                                completePages = pagePaths.size >= engine.listedCount(),
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!engine.isAborted) logcat("TarChunk", e)
                }
            },
        )

        try {
            block(loader)
        } finally {
            engine.abort()
            bgJob.getAndSet(null)?.cancel()
            extractJobs.values.toList().forEach { it.cancel() }
            extractJobs.clear()
        }
    }
}
