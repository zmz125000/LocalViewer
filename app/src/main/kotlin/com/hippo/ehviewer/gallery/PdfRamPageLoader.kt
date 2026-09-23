package com.hippo.ehviewer.gallery

import android.os.SystemClock
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.library.document.PdfImageEngine
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield
import okio.Path

/**
 * Built-in PDF reader: image/comic PDFs use the image-reader cache-off model.
 *
 * Compressed page bytes stay in [ramPages] until decode; [PageLoader] pins decoded
 * bitmaps for the viewport + decode-ahead window. Page-tree offsets persist to
 * [DocumentExtractCache] so an unfinished index resumes after exit.
 *
 * Indexing stays on the engine parser (one walker). Listed pages extract in
 * parallel through [openExtractSource] and do not take that parser.
 */
internal class PdfRamPageLoader(
    scope: CoroutineScope,
    private val engine: PdfImageEngine,
    titleHint: String,
    startPage: Int,
    private val cacheKey: String? = null,
    openExtractSource: (() -> ArchiveByteSource)? = null,
) : PageLoader(
    scope,
    info = null,
    startPage = startPage,
    initialSize = engine.pageCount.coerceAtLeast(1),
) {
    override val title = titleHint

    private val ramPages = ConcurrentHashMap<Int, ByteArray>()
    private val extractPool = openExtractSource?.let { PdfExtractPool(it) }
    private val serialExtractSlots = Semaphore(1)
    private val parallelExtractSlots = Semaphore((PDF_EXTRACT_SLOTS - 1).coerceAtLeast(1))
    private val extractJobs = KeyedJobRegistry<Int>()
    private val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
    private val interactivePending = ConcurrentHashMap.newKeySet<Int>()
    private val backgroundJobs = ConcurrentHashMap.newKeySet<Int>()
    private val deferredExtracts = ConcurrentHashMap.newKeySet<Int>()
    private val sessionClosed = AtomicBoolean(false)
    private val discoveryJob = AtomicReference<Job?>(null)
    private var visiblePages: IntRange? = null

    private var lastSavedCount = 0

    init {
        cacheKey?.let { DocumentExtractCache.pin(it) }
        persistIndex()
        requestDiscovery()
    }

    override fun getImageExtension(index: Int): String? = engine.extOf(index)

    override fun savePage(index: Int, file: Path): Boolean = runCatching {
        val bytes = ramPages[index] ?: return@runCatching false
        File(file.toString()).writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun openSource(index: Int): ImageSource {
        val bytes = ramPages[index]
            ?: throw FileNotFoundException("PDF page $index not in RAM")
        return byteBufferSource(ByteBuffer.wrap(bytes)) {}
    }

    override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
        // Cache-off: do not hold extra compressed pages outside the decode window.
    }

    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
        val interactive = documentExtractIsVisible(index, visiblePages, orgImg)
        ensureExtract(index, interactive) {
            notifySourceReady(index, orgImg)
        }
        if (interactivePending.isEmpty()) requestDiscovery()
    }

    override fun onNavigation(demand: ReaderDemand) {
        visiblePages = demand.navigation.visiblePages
        readyWaiters.forEach { idx, _ ->
            if (idx !in demand.decodedPages) readyWaiters.remove(idx)
        }
        ramPages.keys.toList().forEach { idx ->
            if (idx !in demand.decodedPages) {
                ramPages.remove(idx)
                clearSourceReady(idx)
            }
        }
        extractJobs.cancelOutside(demand.sourcePages)
        demand.visibleDecode.forEach { index ->
            val wasDeferred = deferredExtracts.remove(index)
            val running = extractJobs.owner(index)
            val alreadyVisible = running != null &&
                running.isActive &&
                !backgroundJobs.contains(index)
            if (alreadyVisible || ramPages.containsKey(index)) return@forEach
            if (wasDeferred || readyWaiters.containsKey(index)) {
                ensureExtract(index, interactive = true)
            }
        }
        if (interactivePending.isEmpty()) requestDiscovery()
    }

    override fun releaseRamPage(index: Int) {
        ramPages.remove(index)
        if (!isDecodedDemand(index)) clearSourceReady(index)
    }

    override fun close() {
        sessionClosed.set(true)
        deferredExtracts.clear()
        engine.pauseDiscovery()
        discoveryJob.getAndSet(null)?.cancel()
        extractJobs.cancelAll()
        extractPool?.close()
        readyWaiters.clear()
        ramPages.clear()
        persistIndex()
        cacheKey?.let { DocumentExtractCache.unpin(it) }
        super.close()
    }

    /** Page bodies stay in RAM; only the listed stream table is durable. */
    private fun persistIndex() {
        val key = cacheKey ?: return
        lastSavedCount = engine.pageCount
        DocumentExtractCache.saveIndexAsync(engine.toIndex(key, complete = false))
    }

    private fun ensureExtract(
        index: Int,
        interactive: Boolean,
        onReady: (() -> Unit)? = null,
    ) {
        if (sessionClosed.get() || index !in 0 until size) return
        if (onReady != null) {
            readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
            if (ramPages.containsKey(index)) {
                dispatchReady(index)
                return
            }
        } else if (ramPages.containsKey(index)) {
            return
        }
        // Incomplete index: only the pages on screen. Prefetch and decode-ahead wait.
        if (!interactive && deferDocumentBackgroundWork(engine.structureComplete)) {
            deferredExtracts.add(index)
            return
        }
        deferredExtracts.remove(index)
        if (interactive && extractPool == null) interactivePending.add(index)
        val existing = extractJobs.owner(index)
        if (existing != null && !existing.isCompleted) {
            if (interactive && backgroundJobs.contains(index)) {
                existing.cancel()
                extractJobs.release(index, existing)
            } else {
                if (interactive) interactivePending.remove(index)
                return
            }
        }
        // No side sources: the viewport still has to take the one parser.
        if (interactive && extractPool == null) {
            engine.pauseDiscovery()
            discoveryJob.get()?.cancel()
            backgroundJobs.forEach { idx ->
                if (idx != index) extractJobs.owner(idx)?.cancel()
            }
        }
        if (!interactive) backgroundJobs.add(index)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                ensureActive()
                if (ramPages.containsKey(index)) {
                    dispatchReady(index)
                    return@launch
                }
                extractToRam(index, interactive)
                if (ramPages.containsKey(index)) {
                    dispatchReady(index)
                } else {
                    val waiters = takeReadyWaiters(index)
                    if (waiters.isNotEmpty()) {
                        notifyPageFailed(index, "Extract incomplete")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat("PdfRamLoader", e)
                val waiters = takeReadyWaiters(index)
                if (waiters.isNotEmpty() || interactive) {
                    notifyPageFailed(index, e.message)
                }
            } finally {
                val thisJob = coroutineContext[Job]
                val wonSlot = extractJobs.owns(index, thisJob)
                if (interactive && wonSlot) {
                    interactivePending.remove(index)
                    if (interactivePending.isEmpty() && !sessionClosed.get()) {
                        engine.resumeDiscovery()
                        requestDiscovery()
                    }
                }
                if (!interactive) backgroundJobs.remove(index)
                extractJobs.release(index, thisJob)
            }
        }
        if (extractJobs.register(index, job)) {
            job.start()
        } else {
            job.cancel()
            if (interactive) interactivePending.remove(index)
            if (!interactive) backgroundJobs.remove(index)
        }
    }

    private suspend fun extractToRam(index: Int, interactive: Boolean) {
        if (ramPages.containsKey(index)) return
        if (!interactive && deferDocumentBackgroundWork(engine.structureComplete)) return
        if (index >= engine.pageCount) {
            if (!interactive && !engine.structureComplete) return
            if (interactive) {
                interactivePending.add(index)
                engine.pauseDiscovery()
            }
            try {
                engine.ensureListedThrough(index)
            } finally {
                if (interactive) {
                    interactivePending.remove(index)
                    if (interactivePending.isEmpty()) engine.resumeDiscovery()
                }
            }
        }
        if (index >= engine.pageCount || ramPages.containsKey(index)) return
        // Same stall as cache-off SMB: sourceReady outlives the RAM copy, so a
        // non-visible re-extract polls outside the ordered window.
        clearSourceReady(index)
        withOrderedPermits(
            rank = { pdfExtractOrderRank(prefetchRank(index), interactive) },
            serialSlots = serialExtractSlots,
            fallbackSlots = parallelExtractSlots,
            fallbackPermits = PDF_EXTRACT_SLOTS - 1,
        ) {
            if (ramPages.containsKey(index)) {
                markSourceReady(index)
                return@withOrderedPermits
            }
            val pool = extractPool
            val known = if (pool != null && engine.streamOffsetOf(index) >= 0L) {
                pool.use { source ->
                    engine.extractKnownBytes(index, source) { bitmap ->
                        publishPreparedBitmap(index, bitmap)
                    }
                }
            } else {
                null
            }
            val bytes = known ?: engine.extractBytes(index) { bitmap ->
                publishPreparedBitmap(index, bitmap)
            } ?: return@withOrderedPermits
            if (isDecodedDemand(index)) ramPages[index] = bytes
            if (ramPages.containsKey(index)) markSourceReady(index)
        }
    }

    private fun dispatchReady(index: Int) {
        takeReadyWaiters(index).forEach { runCatching { it() } }
    }

    private fun takeReadyWaiters(index: Int): List<() -> Unit> = readyWaiters.remove(index)?.toList().orEmpty()

    private fun drainDeferredExtracts() {
        if (sessionClosed.get()) {
            deferredExtracts.clear()
            return
        }
        val pending = deferredExtracts.toTypedArray()
        deferredExtracts.clear()
        pending.forEach { ensureExtract(it, interactive = false) }
    }

    /**
     * List playable pages on the parser only. Page bodies extract on [extractPool]
     * and do not take this walker. Without a pool, a visible extract still pauses
     * the walker so it can use the one parser source.
     */
    private fun requestDiscovery() {
        if (sessionClosed.get() || engine.structureComplete) return
        if (interactivePending.isNotEmpty()) return
        while (true) {
            val active = discoveryJob.get()
            if (active?.isActive == true) return
            if (active != null && !discoveryJob.compareAndSet(active, null)) continue
            val job = scope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY,
            ) {
                try {
                    var lastPublishAt = 0L
                    fun publishListed(force: Boolean = false) {
                        val listed = engine.pageCount
                        if (listed <= 0) return
                        val now = SystemClock.elapsedRealtime()
                        if (!force && now - lastPublishAt < PDF_INDEX_PUBLISH_MS) return
                        lastPublishAt = now
                        growTo(listed)
                    }
                    while (!engine.structureComplete) {
                        ensureActive()
                        if (interactivePending.isNotEmpty()) {
                            delay(PDF_INDEX_YIELD_MS)
                            continue
                        }
                        val before = engine.pageCount
                        val after = try {
                            ensureActive()
                            if (interactivePending.isNotEmpty()) {
                                before
                            } else {
                                engine.ensureListedThrough(before)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        }
                        if (after > before) {
                            publishListed()
                            if (engine.structureComplete ||
                                after - lastSavedCount >= PDF_INDEX_SAVE_EVERY
                            ) {
                                persistIndex()
                            }
                        } else {
                            if (interactivePending.isNotEmpty()) {
                                delay(PDF_INDEX_YIELD_MS)
                                continue
                            }
                            break
                        }
                        yield()
                    }
                    publishListed(force = true)
                    persistIndex()
                    if (engine.structureComplete) {
                        drainDeferredExtracts()
                        if (!sessionClosed.get()) replan()
                    }
                } catch (e: CancellationException) {
                    growTo(engine.pageCount)
                    persistIndex()
                    throw e
                } catch (e: Throwable) {
                    logcat("PdfRamIndex", e)
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
}
