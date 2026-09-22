package com.hippo.ehviewer.gallery

import android.os.SystemClock
import arrow.autoCloseScope
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.PfdArchiveByteSource
import com.hippo.ehviewer.library.document.DocumentImageEngine
import com.hippo.ehviewer.library.document.EpubEngine
import com.hippo.ehviewer.library.document.PdfImageEngine
import com.hippo.ehviewer.library.document.ProgressiveDocumentImageEngine
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isPdfFileName
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import moe.tarsin.kt.install
import okio.Path

/**
 * Image-only document reader (PDF/EPUB): index + extract pages into
 * [DocumentExtractCache], same delivery model as solid/stream.
 *
 * Does **not** hold [com.hippo.ehviewer.library.ArchiveAccess] (pure Kotlin ZIP / PDF).
 */
suspend inline fun <T> useDocumentExtractPageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    formatHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    remoteSize: Long = 0L,
    /** Network PDFs may publish a prefix and grow their page list on demand. */
    progressivePdf: Boolean = false,
    /** Optional local path for library page-count updates. */
    localPathForLibrary: String? = null,
    crossinline block: suspend (PageLoader) -> T,
): T = autoCloseScope {
    coroutineScope {
        val sizeHint = remoteSize.takeIf { it > 0L }
            ?: runCatching { source.size }.getOrDefault(0L)
        DocumentExtractCache.invalidateIfRemoteSizeMismatch(cacheKey, sizeHint)
        DocumentExtractCache.pin(cacheKey)
        install({ }, { _, _ -> DocumentExtractCache.unpin(cacheKey) })
        install({ source }, { s, _ -> s.close() })

        val ready = DocumentExtractCache.isCompleteAndReady(cacheKey, remoteSize = sizeHint)
        if (ready != null) {
            DocumentExtractCache.touchAsync(cacheKey)
            val loader = install(
                cachedDocumentLoader(
                    scope = this,
                    cacheKey = cacheKey,
                    docIndex = ready,
                    titleHint = titleHint,
                    info = info,
                    startPage = startPage,
                    hasAds = hasAds,
                ),
            )
            return@coroutineScope block(loader)
        }

        check(sizeHint > 0L) { "Cannot open document (size unknown): $cacheKey" }

        // Prefer durable page list: skip PDF page-tree / EPUB OPF on reopen.
        val cachedIdx = DocumentExtractCache.loadUsableIndex(cacheKey, remoteSize = sizeHint)
        // Close engine with the reader session (DocumentImageEngine is AutoCloseable).
        val engine: DocumentImageEngine = install(
            {
                openDocumentEngine(
                    source = source,
                    sizeHint = sizeHint,
                    formatHint = formatHint,
                    titleHint = titleHint,
                    cacheKey = cacheKey,
                    cachedIndex = cachedIdx,
                    progressivePdf = progressivePdf,
                )
            },
            { value, _ -> value.close() },
        )
        val progressiveEngine = engine as? ProgressiveDocumentImageEngine

        check(engine.pageCount > 0) { "Document has no playable images" }

        // Persist index early (incomplete until all pages extracted).
        DocumentExtractCache.saveIndex(engine.toIndex(cacheKey, complete = false))

        val pagePaths = ConcurrentHashMap<Int, Path>()
        val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
        val extractJobs = ConcurrentHashMap<Int, Job>()
        val backgroundJobs = ConcurrentHashMap.newKeySet<Int>()
        val interactivePending = ConcurrentHashMap.newKeySet<Int>()
        val deferredExtracts = ConcurrentHashMap.newKeySet<Int>()
        val extractMutex = Mutex()
        var visiblePages: IntRange? = null
        val coverWritten = AtomicBoolean(false)
        val sessionClosed = AtomicBoolean(false)
        val discoveryJob = AtomicReference<Job?>(null)
        val hostScope = this
        val resumePage = startPage.coerceIn(0, (engine.pageCount - 1).coerceAtLeast(0))

        // Seed the page the reader will actually show.
        val cachedResume = DocumentExtractCache.findCachedPage(cacheKey, resumePage)
        if (cachedResume != null) {
            pagePaths[resumePage] = cachedResume
        } else {
            extractMutex.withLock {
                engine.extractToCache(cacheKey, resumePage)?.let { pagePaths[resumePage] = it }
            }
            check(
                pagePaths[resumePage] != null ||
                    DocumentExtractCache.isPageCached(
                        cacheKey,
                        resumePage,
                        engine.extOf(resumePage) ?: "bin",
                    ),
            ) {
                "Failed to extract document page $resumePage"
            }
            pagePaths[resumePage] = pagePaths[resumePage]
                ?: DocumentExtractCache.pagePath(
                    cacheKey,
                    resumePage,
                    engine.extOf(resumePage) ?: "bin",
                )
        }

        // Reuse page 0 for the cover when it is already cached. Do not fetch it ahead
        // of a different resume page just for metadata.
        if (resumePage != 0) {
            DocumentExtractCache.findCachedPage(cacheKey, 0)?.let { pagePaths[0] = it }
        }

        // Cover / library metadata after loader publish (never blocks open on encode).
        val page0Cached = pagePaths[0]
        if (page0Cached != null && coverWritten.compareAndSet(false, true)) {
            ArchiveCoverCache.scheduleEncodeFromExtractedPage(cacheKey, page0Cached) { cover ->
                localPathForLibrary?.let { pathStr ->
                    val resolved = cover ?: ArchiveCoverCache.tryDiskCover(pathStr)
                    val gid = info?.gid
                    if (gid != null && gid != 0L) {
                        LocalLibrary.updateGalleryPageAndCover(
                            gid,
                            engine.pageCount,
                            resolved?.toString(),
                        )
                    } else {
                        LocalLibrary.updateGalleryPageAndCoverByContentPath(
                            pathStr,
                            engine.pageCount,
                            resolved?.toString(),
                        )
                    }
                }
            }
        }

        val loader = install(
            object : PageLoader(
                hostScope,
                info,
                resumePage,
                engine.pageCount,
                hasAds,
            ) {
                override val title by lazy { info?.title ?: titleHint }

                init {
                    requestDiscovery()
                }

                override fun getImageExtension(index: Int): String? = engine.extOf(index)
                    ?: pagePaths[index]?.name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
                    ?: DocumentExtractCache.findCachedPage(cacheKey, index)?.name?.substringAfterLast('.', "")

                override fun savePage(index: Int, file: Path): Boolean = runCatching {
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.findCachedPage(cacheKey, index)
                        ?: engine.extOf(index)?.let { ext ->
                            DocumentExtractCache.pagePath(cacheKey, index, ext)
                                .takeIf { DocumentExtractCache.isCachedFile(it) }
                        }
                        ?: error("Not cached")
                    pagePaths[index] = path
                    File(path.toString()).copyTo(File(file.toString()), overwrite = true)
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.findCachedPage(cacheKey, index)
                        ?: run {
                            val ext = engine.extOf(index) ?: "bin"
                            DocumentExtractCache.pagePath(cacheKey, index, ext)
                                .takeIf { DocumentExtractCache.isCachedFile(it) }
                        }
                        ?: throw java.io.FileNotFoundException("Document page $index not extracted")
                    pagePaths[index] = path
                    val ext = path.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
                        ?: engine.extOf(index) ?: "bin"
                    return object : PathSource {
                        override val source: Path = path
                        override val type: String = ext
                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    // PdfParser is 1-wide. Prefetch Range reads must not stall the page
                    // tree; skip until listing finishes. Keep at most one opportunistic
                    // extraction active afterwards.
                    if (deferDocumentBackgroundWork(progressiveEngine?.structureComplete ?: true)) {
                        return
                    }
                    pages.firstOrNull { !isPageMapped(it) }?.let {
                        ensureExtract(it, interactive = false)
                    }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    // Only the viewport may snatch [extractMutex] from discovery.
                    // Decode-ahead used to be interactive and cancelled the walk for the
                    // first preload window (typically 3 decode + 5 source pages).
                    val interactive = documentExtractIsVisible(index, visiblePages, orgImg)
                    ensureExtract(index, interactive) {
                        notifySourceReady(index, orgImg)
                    }
                    if (interactivePending.isEmpty()) requestDiscovery()
                }

                override fun onNavigation(demand: ReaderDemand) {
                    visiblePages = demand.navigation.visiblePages
                    // Decode-ahead may already own inflight and have deferred extract.
                    // requestDecode is then a no-op — promote the new viewport here.
                    demand.visibleDecode.forEach { index ->
                        val wasDeferred = deferredExtracts.remove(index)
                        val running = extractJobs[index]
                        val alreadyVisible = running != null &&
                            running.isActive &&
                            !backgroundJobs.contains(index)
                        if (alreadyVisible || isPageMapped(index)) return@forEach
                        if (wasDeferred || readyWaiters.containsKey(index)) {
                            ensureExtract(index, interactive = true)
                        }
                    }
                    if (interactivePending.isEmpty()) requestDiscovery()
                }

                override fun close() {
                    // Snapshot: cancel handlers remove from extractJobs concurrently
                    // (live CHM.values iter on main → NoSuchElementException).
                    sessionClosed.set(true)
                    deferredExtracts.clear()
                    progressiveEngine?.pauseDiscovery()
                    discoveryJob.getAndSet(null)?.cancel()
                    extractJobs.values.toList().forEach { it.cancel() }
                    extractJobs.clear()
                    readyWaiters.clear()
                    // Trust in-memory extract map only — never stat/write on main (onDispose).
                    val count = engine.pageCount
                    val structureComplete = progressiveEngine?.structureComplete ?: true
                    val complete = count > 0 &&
                        structureComplete &&
                        (0 until count).all { pagePaths.containsKey(it) }
                    DocumentExtractCache.saveIndexAsync(
                        engine.toIndex(cacheKey, complete = complete),
                    )
                    // Unblock any smbj read waiting on the network source (back / hop).
                    runCatching { source.close() }
                    super.close()
                }

                /** In-memory only — safe on main / onDispose. */
                private fun isPageMapped(index: Int): Boolean = pagePaths.containsKey(index)

                /** Disk probe; call only from [Dispatchers.IO]. */
                private fun probePageOnDisk(index: Int): Boolean {
                    if (pagePaths.containsKey(index)) return true
                    val cached = DocumentExtractCache.findCachedPage(cacheKey, index)
                    if (cached != null) {
                        pagePaths[index] = cached
                        return true
                    }
                    val ext = engine.extOf(index) ?: return false
                    val p = DocumentExtractCache.pagePath(cacheKey, index, ext)
                    if (DocumentExtractCache.isCachedFile(p)) {
                        pagePaths[index] = p
                        return true
                    }
                    return false
                }

                private fun markReady(index: Int) {
                    val path = pagePaths[index] ?: return
                    if (index == 0 && coverWritten.compareAndSet(false, true)) {
                        ArchiveCoverCache.scheduleEncodeFromExtractedPage(cacheKey, path) { cover ->
                            localPathForLibrary?.let { pathStr ->
                                val resolved = cover ?: ArchiveCoverCache.tryDiskCover(pathStr)
                                val gid = info?.gid
                                if (gid != null && gid != 0L) {
                                    LocalLibrary.updateGalleryPageAndCover(
                                        gid,
                                        engine.pageCount,
                                        resolved?.toString(),
                                    )
                                } else {
                                    LocalLibrary.updateGalleryPageAndCoverByContentPath(
                                        pathStr,
                                        engine.pageCount,
                                        resolved?.toString(),
                                    )
                                }
                            }
                        }
                    }
                    readyWaiters.remove(index)?.forEach { runCatching { it() } }
                }

                private fun drainDeferredExtracts() {
                    if (sessionClosed.get()) {
                        deferredExtracts.clear()
                        return
                    }
                    val pending = deferredExtracts.toTypedArray()
                    deferredExtracts.clear()
                    pending.forEach { ensureExtract(it, interactive = false) }
                }

                private fun ensureExtract(
                    index: Int,
                    interactive: Boolean,
                    onReady: (() -> Unit)? = null,
                ) {
                    if (sessionClosed.get() || index !in 0 until engine.pageCount) return
                    if (onReady != null) {
                        readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                        if (isPageMapped(index)) {
                            markReady(index)
                            return
                        }
                    } else if (isPageMapped(index)) {
                        return
                    }
                    if (!interactive &&
                        deferDocumentBackgroundWork(progressiveEngine?.structureComplete ?: true)
                    ) {
                        deferredExtracts.add(index)
                        return
                    }
                    deferredExtracts.remove(index)
                    if (interactive) {
                        interactivePending.add(index)
                    }
                    val existing = extractJobs[index]
                    if (existing != null && existing.isActive) {
                        if (interactive && backgroundJobs.contains(index)) {
                            // Upgrade an enqueued prefetch into a visible-page request.
                            existing.cancel()
                            extractJobs.remove(index, existing)
                        } else {
                            if (interactive) interactivePending.remove(index)
                            return
                        }
                    }
                    if (interactive) {
                        // Stop the current tree-walk step (not the whole job queue) so
                        // extract can take the parser. Mapped pages return above and must
                        // not pause: growTo → replan re-requests the current page.
                        progressiveEngine?.pauseDiscovery()
                        discoveryJob.get()?.cancel()
                    }
                    if (!interactive) backgroundJobs.add(index)
                    val job = hostScope.launch(Dispatchers.IO) {
                        try {
                            ensureActive()
                            if (interactive) runCatching { source.dropQueuedReads() }
                            if (probePageOnDisk(index)) {
                                markReady(index)
                                return@launch
                            }
                            val waitForMutex = interactive || readyWaiters.containsKey(index)
                            if (waitForMutex) {
                                extractMutex.withLock {
                                    ensureActive()
                                    if (!probePageOnDisk(index)) {
                                        engine.extractToCache(cacheKey, index)?.let { pagePaths[index] = it }
                                    }
                                }
                            } else {
                                // Opportunistic prefetch: never queue behind extract/index.
                                if (interactivePending.isNotEmpty() ||
                                    deferDocumentBackgroundWork(
                                        progressiveEngine?.structureComplete ?: true,
                                    ) ||
                                    !extractMutex.tryLock()
                                ) {
                                    return@launch
                                }
                                try {
                                    ensureActive()
                                    if (interactivePending.isEmpty() && !probePageOnDisk(index)) {
                                        engine.extractToCache(cacheKey, index)?.let { pagePaths[index] = it }
                                    }
                                } finally {
                                    extractMutex.unlock()
                                }
                            }
                            if (probePageOnDisk(index)) {
                                markReady(index)
                                if (
                                    progressiveEngine?.structureComplete ?: true &&
                                    pagePaths.size >= engine.pageCount
                                ) {
                                    DocumentExtractCache.saveIndexAsync(
                                        engine.toIndex(cacheKey, complete = true),
                                    )
                                }
                            } else {
                                val waiters = readyWaiters.remove(index).orEmpty()
                                if (waiters.isNotEmpty()) {
                                    notifyPageFailed(index, "Extract incomplete")
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logcat("DocumentExtract", e)
                            val waiters = readyWaiters.remove(index).orEmpty()
                            if (waiters.isNotEmpty() || interactive) {
                                notifyPageFailed(index, e.message)
                            }
                        } finally {
                            val thisJob = coroutineContext[Job]
                            val wonSlot = extractJobs[index] === thisJob ||
                                extractJobs[index] == null
                            if (interactive && wonSlot) {
                                interactivePending.remove(index)
                                if (interactivePending.isEmpty() && !sessionClosed.get()) {
                                    progressiveEngine?.resumeDiscovery()
                                    requestDiscovery()
                                }
                            }
                            if (!interactive) backgroundJobs.remove(index)
                            extractJobs.remove(index, thisJob)
                        }
                    }
                    val prev = extractJobs.putIfAbsent(index, job)
                    if (prev != null) {
                        if (prev.isActive) {
                            job.cancel()
                            if (!interactive) backgroundJobs.remove(index)
                        } else {
                            extractJobs[index] = job
                        }
                    }
                }

                /**
                 * Keep listing playable PDF pages until the page tree ends.
                 *
                 * Discovery shares [extractMutex] with [extractToCache] because [PdfParser]
                 * is not concurrent-safe. Priority while incomplete:
                 * visible extract > discovery > decode-ahead / prefetch.
                 * [tryLock] so a visible extract never waits behind a queued tree-walk;
                 * [pauseDiscovery] aborts the in-flight cursor step.
                 * [growTo] (seek bar / pager) is published at most every
                 * [PDF_INDEX_PUBLISH_MS], same update as the old per-page grow.
                 */
                private fun requestDiscovery() {
                    val progressive = progressiveEngine ?: return
                    if (sessionClosed.get() || progressive.structureComplete) return
                    if (interactivePending.isNotEmpty()) return
                    while (true) {
                        val active = discoveryJob.get()
                        if (active?.isActive == true) return
                        if (active != null && !discoveryJob.compareAndSet(active, null)) continue
                        val job = hostScope.launch(
                            context = Dispatchers.IO,
                            start = CoroutineStart.LAZY,
                        ) {
                            try {
                                var lastSavedCount = progressive.pageCount
                                var lastPublishAt = 0L
                                fun publishListed(force: Boolean = false) {
                                    val listed = progressive.pageCount
                                    if (listed <= 0) return
                                    val now = SystemClock.elapsedRealtime()
                                    if (!force && now - lastPublishAt < PDF_INDEX_PUBLISH_MS) return
                                    lastPublishAt = now
                                    growTo(listed)
                                }
                                while (!progressive.structureComplete) {
                                    ensureActive()
                                    // Prefer visible-page extract over page-tree Range storms.
                                    if (interactivePending.isNotEmpty()) {
                                        delay(PDF_INDEX_YIELD_MS)
                                        continue
                                    }
                                    // Never queue behind extract — tryLock + yield instead of
                                    // withLock, which used to starve the visible page.
                                    if (!extractMutex.tryLock()) {
                                        delay(PDF_INDEX_YIELD_MS)
                                        continue
                                    }
                                    val before = progressive.pageCount
                                    val after = try {
                                        ensureActive()
                                        if (interactivePending.isNotEmpty()) {
                                            before
                                        } else {
                                            progressive.ensureListedThrough(before)
                                        }
                                    } finally {
                                        extractMutex.unlock()
                                    }
                                    if (after > before) {
                                        publishListed()
                                        val shouldSave = progressive.structureComplete ||
                                            after - lastSavedCount >= PDF_INDEX_SAVE_EVERY
                                        if (shouldSave) {
                                            lastSavedCount = after
                                            DocumentExtractCache.saveIndexAsync(
                                                progressive.toIndex(cacheKey, complete = false),
                                            )
                                        }
                                    } else {
                                        // No progress: paused for extract, transport blip, or true
                                        // end. Do not spin hot; extract/onNavigation restarts us.
                                        if (interactivePending.isNotEmpty()) {
                                            delay(PDF_INDEX_YIELD_MS)
                                            continue
                                        }
                                        break
                                    }
                                    yield()
                                }
                                publishListed(force = true)
                                if (progressive.structureComplete) {
                                    DocumentExtractCache.saveIndexAsync(
                                        progressive.toIndex(cacheKey, complete = false),
                                    )
                                    drainDeferredExtracts()
                                    if (!sessionClosed.get()) replan()
                                }
                            } catch (e: CancellationException) {
                                growTo(progressive.pageCount)
                                throw e
                            } catch (e: Throwable) {
                                logcat("PdfProgressiveIndex", e)
                            } finally {
                                discoveryJob.compareAndSet(coroutineContext[Job], null)
                            }
                        }
                        if (discoveryJob.compareAndSet(null, job)) {
                            job.start()
                            return
                        }
                        job.cancel()
                    }
                }
            },
        )
        block(loader)
    }
}

/**
 * Persist progressive PDF structure every N newly listed images (TAR persists ~every 24).
 * Sync [DocumentExtractCache.saveIndex] under discovery used to freeze scroll while encoding
 * a large JSON index on the same mutex path as the visible page extract.
 */
@PublishedApi
internal const val PDF_INDEX_SAVE_EVERY = 16

/** Publish [PageLoader.growTo] at this interval while the page list is still growing. */
@PublishedApi
internal const val PDF_INDEX_PUBLISH_MS = 500L

/** Back off while interactive pages wait for [extractMutex]. */
@PublishedApi
internal const val PDF_INDEX_YIELD_MS = 16L

/**
 * PdfParser is 1-wide. Decode-ahead / prefetch are multi-megabyte Range reads
 * and must not run until the page tree is listed.
 */
@PublishedApi
internal fun deferDocumentBackgroundWork(structureComplete: Boolean): Boolean = !structureComplete

/** Viewport (or original-size) pages may snatch the parser from discovery. */
@PublishedApi
internal fun documentExtractIsVisible(
    index: Int,
    visiblePages: IntRange?,
    orgImg: Boolean,
): Boolean = orgImg || visiblePages?.let { index in it } ?: true

suspend inline fun <T> useLocalDocumentExtractPageLoader(
    file: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline block: suspend (PageLoader) -> T,
): T {
    val pathStr = file.toString()
    val name = file.name
    val format = when {
        isEpubFileName(name) -> "epub"
        isPdfFileName(name) -> "pdf"
        else -> error("Not a document: $name")
    }
    val pfd = file.openFileDescriptor("r")
    val source = PfdArchiveByteSource(pfd, ownsPfd = true)
    // Full filename incl. extension (pdf/epub).
    val titleHint = name.ifEmpty { "Document" }
    // cacheKey = path string so browse thumbs share the same document_extract + cover key.
    return useDocumentExtractPageLoader(
        source = source,
        cacheKey = pathStr,
        titleHint = titleHint,
        formatHint = format,
        info = info,
        startPage = startPage,
        hasAds = hasAds,
        remoteSize = runCatching { source.size }.getOrDefault(0L),
        localPathForLibrary = pathStr,
        progressivePdf = format == "pdf",
        block = block,
    )
}

@PublishedApi
internal fun openDocumentEngine(
    source: ArchiveByteSource,
    sizeHint: Long,
    formatHint: String,
    titleHint: String,
    cacheKey: String,
    cachedIndex: DocumentExtractCache.Index? = null,
    progressivePdf: Boolean = false,
): DocumentImageEngine {
    val isEpub = formatHint == "epub" ||
        isEpubFileName(titleHint) ||
        isEpubFileName(cacheKey) ||
        cacheKey.endsWith(".epub", ignoreCase = true) ||
        titleHint.endsWith(".epub", ignoreCase = true) ||
        cachedIndex?.format == "epub"
    val isPdf = formatHint == "pdf" ||
        isPdfFileName(titleHint) ||
        isPdfFileName(cacheKey) ||
        cacheKey.endsWith(".pdf", ignoreCase = true) ||
        titleHint.endsWith(".pdf", ignoreCase = true) ||
        cachedIndex?.format == "pdf"
    return when {
        isEpub -> {
            // Bind elvis to the whole if (not only the last branch).
            val engine = if (cachedIndex != null) {
                EpubEngine.openFromIndex(source, cachedIndex, remoteSize = sizeHint)
                    ?: EpubEngine.open(source, remoteSize = sizeHint, coverOnly = false)
            } else {
                EpubEngine.open(source, remoteSize = sizeHint, coverOnly = false)
            }
            engine ?: error("Not a readable EPUB/ZIP")
        }
        isPdf -> {
            // Same: openFromIndex / open are nullable; elvis must cover both branches.
            val engine = if (cachedIndex != null) {
                PdfImageEngine.openFromIndex(
                    source,
                    cachedIndex,
                    remoteSize = sizeHint,
                    progressive = progressivePdf,
                ) ?: PdfImageEngine.open(
                    source,
                    remoteSize = sizeHint,
                    coverOnly = false,
                    progressive = progressivePdf,
                )
            } else {
                PdfImageEngine.open(
                    source,
                    remoteSize = sizeHint,
                    coverOnly = false,
                    progressive = progressivePdf,
                )
            }
            engine ?: error("Not a readable PDF (encrypted or unsupported)")
        }
        else -> error("Unsupported document format: $formatHint")
    }
}

@PublishedApi
internal fun cachedDocumentLoader(
    scope: CoroutineScope,
    cacheKey: String,
    docIndex: DocumentExtractCache.Index,
    titleHint: String,
    info: GalleryInfo?,
    startPage: Int,
    hasAds: Boolean,
): PageLoader {
    val pageCount = docIndex.members.size
    val exts = docIndex.members.associate { it.i to it.ext }
    return object : PageLoader(
        scope,
        info,
        startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount,
        hasAds,
    ) {
        override val title by lazy { info?.title ?: titleHint }

        override fun getImageExtension(index: Int) = exts[index]

        override fun savePage(index: Int, file: Path): Boolean = runCatching {
            val ext = exts[index] ?: return@runCatching false
            val path = DocumentExtractCache.pagePath(cacheKey, index, ext)
            File(path.toString()).copyTo(File(file.toString()), overwrite = true)
            true
        }.getOrDefault(false)

        override fun openSource(index: Int): ImageSource {
            // Index already verified complete — construct path, no open-time File.stat.
            val ext = exts[index] ?: "bin"
            val path = DocumentExtractCache.pagePath(cacheKey, index, ext)
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
