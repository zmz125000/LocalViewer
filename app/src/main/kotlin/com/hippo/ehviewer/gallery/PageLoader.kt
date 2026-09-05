package com.hippo.ehviewer.gallery

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.bracketCase
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.hdr.DisplaySource
import com.hippo.ehviewer.image.hdr.LibDirectDecode
import com.hippo.ehviewer.image.hdr.classify
import com.hippo.ehviewer.image.hdr.classifyPath
import com.hippo.ehviewer.image.hdr.isHeicImageExtension
import com.hippo.ehviewer.image.hdr.needsLibDecode
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import com.hippo.ehviewer.util.detectAds
import com.hippo.ehviewer.util.displayString
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock as mutexWithLock
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.coroutines.NamedMutex
import moe.tarsin.coroutines.withLock
import okio.Path

private val progressScope = CoroutineScope(Dispatchers.IO)

private const val PERSIST_DEBOUNCE_MS = 1_000L

/** Publish [PageLoader] size Snapshot updates onto the main looper. */
private val pageLoaderMainHandler = Handler(Looper.getMainLooper())

/**
 * Bound decoded-page cache to the **Java heap**, not device RAM.
 *
 * Previous logic used `totalMemory / 8` with a 256 MiB floor. On many devices
 * `Runtime.maxMemory()` is only **256 MiB** (growth limit in OOM dumps), so the
 * cache alone could claim the entire heap → OOM allocating 32 bytes after GC
 * with <1% free — independent of SMB multiplex. SMB high throughput only
 * made decode/download finish faster and fill the cache sooner.
 */
private fun pageImageCacheMaxBytes(): Int {
    val heap = OSUtils.appMaxMemory.coerceAtLeast(64L * 1024 * 1024)
    // Cache-off keeps compressed pages in the Java heap until decode finishes;
    // leave more headroom than the disk-backed path.
    val ram = Settings.disableReaderNetworkCache.value
    val targetPct = if (ram) 25L else 35L
    val capBytes = if (ram) 96L * 1024 * 1024 else 160L * 1024 * 1024
    val capPct = if (ram) 30L else 45L
    val target = (heap * targetPct / 100).toInt()
    val min = (24L * 1024 * 1024).toInt()
    val max = minOf(capBytes.toInt(), (heap * capPct / 100).toInt())
    return target.coerceIn(min, max.coerceAtLeast(min))
}

abstract class PageLoader(
    val scope: CoroutineScope,
    override val info: GalleryInfo?,
    startPage: Int,
    initialSize: Int,
    val hasAds: Boolean = false,
) : ReaderSession {
    /**
     * Page count. Snapshot-backed so Compose pager/list recompose when solid extract
     * grows the lazy list. [growTo] publishes on the main thread.
     */
    private val sizeState = mutableIntStateOf(initialSize.coerceAtLeast(0))

    /** Observable page count (Compose Snapshot). Seek bar / pager must read this. */
    override val size: Int
        get() = sizeState.intValue

    override var startPage = if (size <= 0) 0 else startPage.coerceIn(0, size - 1)
        set(value) {
            val next = if (size <= 0) 0 else value.coerceIn(0, (size - 1).coerceAtLeast(0))
            if (field == next) return
            field = next
            schedulePersistProgress()
        }

    private val jobs = HashMap<Int, Job>()

    /**
     * Indices that have entered [onRequest] / decode and are not yet Ready/Error/cancelled.
     * Covers the download/extract window **before** [notifySourceReady] puts a job in [jobs],
     * so a second [request] (status collect, dual mate, slider) cannot start the same page twice.
     * Guarded by the same lock as [jobs].
     */
    private val inflight = HashSet<Int>()
    private val forcedDecode = HashSet<Int>()
    private val mutex = NamedMutex<Int>()

    /**
     * Peak software decode is large; keep concurrency low on a 256 MiB heap.
     * Cache-off also holds compressed bytes on the heap, so decode is 2-wide.
     * Lib-direct F16 is further serialized inside [LibDirectDecode] (one at a time).
     */
    private val semaphore = Semaphore(
        when {
            Settings.disableReaderNetworkCache.value -> 2
            Settings.readerLibDirectBitmap.value -> 2
            else -> 4
        },
    )

    /**
     * Decoded-page budget. Weight is clamped so one huge bitmap can occupy the
     * cache instead of being inserted and immediately evicted.
     *
     * [LruCache], not androidx SieveCache: Sieve `put` trims before linking, and
     * after ~255 unique pages that crashes with `length=255; index=2147483647`.
     */
    private val imageCacheMaxBytes = pageImageCacheMaxBytes()

    private val cache = object : LruCache<Int, Image>(imageCacheMaxBytes) {
        override fun sizeOf(key: Int, value: Image): Int = cacheWeightOf(value)

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Image, newValue: Image?) {
            if (oldValue !== newValue) oldValue.unpin()
        }
    }

    private fun cacheWeightOf(image: Image): Int {
        val estimated = image.estimatedCacheBytes
        if (estimated <= 0L) return 1
        return estimated.coerceAtMost(imageCacheMaxBytes.toLong()).toInt().coerceAtLeast(1)
    }

    private suspend fun atomicallyDecodeAndUpdate(index: Int, forceOriginal: Boolean) {
        // Local archives: ByteBuffer from mmap extract stays in memory (Coil data(buffer)).
        // Lib stills (JXL/JXR/PQ-AVIF) convert to UHDR jpeg even when network cache is off —
        // ImageDecoder cannot open those codecs. Folder/network PathSource as before.
        // Experimental [Settings.readerLibDirectBitmap]: lib → Bitmap, skip convert.
        bracketCase(
            { openSource(index) },
            { raw ->
                val checkAds = hasAds && detectAds(index, size)
                val hint = getImageExtension(index)?.let { "page.$it" } ?: "page.bin"
                val persistTo = if (Settings.disableReaderNetworkCache.value) {
                    convertDestPath(index)
                } else {
                    null
                }
                val image = tryDecodeLibDirect(raw, forceOriginal, hint)
                    ?: run {
                        val ready = DisplaySource.ensureReady(raw, hint, persistTo = persistTo)
                        if (persistTo != null && ready is PathSource) {
                            releaseRamPage(index)
                        }
                        Image.decode(
                            ready,
                            checkExtraneousAds = checkAds,
                            forceOriginal = forceOriginal,
                        )
                    }
                // Compressed ramPages are only needed until decode. Keep them while this
                // index is still demanded (save / retry); drop as soon as the bitmap exists
                // if navigation already moved on.
                if (!isDecodedDemand(index)) releaseRamPage(index)
                try {
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    image.unpin()
                    throw e
                }
                val runningJob = currentCoroutineContext()[Job]
                if (!commitDecodedImage(index, image, runningJob)) {
                    // Navigation changed or this job was replaced before publication.
                    image.unpin()
                }
            },
            { src, case -> if (case !is ExitCase.Completed) src.close() },
        )
    }

    /**
     * When [Settings.readerLibDirectBitmap] is on and the page is a lib still,
     * decode straight to Bitmap. Null → fall through to convert + Coil.
     */
    private suspend fun tryDecodeLibDirect(
        raw: ImageSource,
        forceOriginal: Boolean,
        hint: String,
    ): Image? {
        if (!Settings.readerLibDirectBitmap.value) return null
        val nameHint = when (raw) {
            is PathSource -> raw.source.name.ifBlank { hint }
            else -> hint
        }
        val route = when (raw) {
            is PathSource -> classifyPath(raw.source, nameHint)
            is ByteBufferSource -> classify(raw.source, nameHint)
        }
        if (!route.needsLibDecode) return null
        val maxEdge = Image.maxEdgeForReader(forceOriginal)
        val direct = LibDirectDecode.decode(raw, nameHint, maxEdge) ?: return null
        return Image.fromLibDirect(direct, raw)
    }

    private val lock = ReentrantReadWriteLock()

    private val pageList = ArrayList<Page>(initialSize.coerceAtLeast(1)).apply {
        repeat(initialSize.coerceAtLeast(0)) { add(Page(it)) }
    }

    /** Live page slots; grows with [growTo] for solid lazy lists. */
    override val pages: List<Page> get() = pageList

    /**
     * Expand lazy list to [newSize] (seek bar + pager max). Only grows; never shrinks.
     *
     * Public: solid extract is inlined into the reader; coroutines must call this without
     * protected cross-package access. Size is published on the main looper so Compose
     * Snapshot invalidates HorizontalPager / LazyColumn (IO-thread writes alone do not).
     */
    fun growTo(newSize: Int) {
        if (newSize <= pageList.size && newSize <= sizeState.intValue) return
        val published: Int
        synchronized(pageList) {
            while (pageList.size < newSize) {
                pageList.add(Page(pageList.size))
            }
            published = pageList.size
        }
        publishSize(published)
    }

    private fun publishSize(n: Int) {
        if (n <= sizeState.intValue) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (n > sizeState.intValue) {
                sizeState.intValue = n
                replan()
            }
        } else {
            pageLoaderMainHandler.post {
                if (n > sizeState.intValue) {
                    sizeState.intValue = n
                    replan()
                }
            }
        }
    }

    private val demandPlanner = ReaderDemandPlanner()

    @Volatile
    private var lastNavigation: ReaderNavigation? = null

    @Volatile
    private var desiredDecodedPages: Set<Int> = emptySet()

    override fun restart() {
        cancelDecodeJobs()
        lock.write { cache.evictAll() }
        pages.forEach(Page::reset)
        replan()
    }

    /** Drop decode work outside the explicit visible + decode-ahead demand set. */
    private fun prioritizeDecode(desired: Set<Int>) {
        val obsolete = synchronized(jobs) {
            val staleJobs = jobs.entries
                .filter { (jobIndex, job) -> job.isActive && jobIndex !in desired }
                .map { it.key to it.value }
            staleJobs.forEach { (jobIndex, _) ->
                jobs.remove(jobIndex)
                inflight.remove(jobIndex)
                forcedDecode.remove(jobIndex)
            }
            // Download/extract may be claimed with no decode job yet — drop far claims so
            // a later scroll-back can request again instead of sticking forever.
            inflight.filter { it !in desired }.forEach { inflight.remove(it) }
            forcedDecode.filter { it !in desired }.forEach { forcedDecode.remove(it) }
            staleJobs
        }
        obsolete.forEach { (_, job) -> job.cancel() }
    }

    private fun cancelDecodeJobs() {
        val active = synchronized(jobs) {
            inflight.clear()
            forcedDecode.clear()
            jobs.values.toList().also { jobs.clear() }
        }
        active.forEach { it.cancel() }
    }

    /** True if this call now owns [index]; false if download/decode is already running. */
    private fun claimInflight(index: Int): Boolean = synchronized(jobs) {
        if (index in inflight || jobs[index]?.isActive == true) return false
        inflight.add(index)
        true
    }

    private fun releaseInflight(index: Int) {
        synchronized(jobs) {
            inflight.remove(index)
            forcedDecode.remove(index)
        }
    }

    private fun isDecodeDemanded(index: Int): Boolean = index in desiredDecodedPages || synchronized(jobs) { index in forcedDecode }

    private fun ownsDecodeSlot(index: Int, job: Job?): Boolean = synchronized(jobs) {
        job != null && jobs[index] === job
    }

    override fun retryPage(index: Int, orgImg: Boolean) {
        cancelRequest(index)
        notifyPageWait(index)
        lock.write { cache.remove(index) }
        if (index !in 0 until size) return
        if (!claimInflight(index)) return
        synchronized(jobs) { forcedDecode.add(index) }
        try {
            onRequest(index, true, orgImg)
        } catch (e: Throwable) {
            releaseInflight(index)
            notifyPageFailed(index, e.displayString())
        }
    }

    protected abstract fun prefetchPages(pages: List<Int>, bounds: IntRange)

    /**
     * @param orgImg if true, force full-resolution decode for this page (page menu).
     *   Otherwise uses [Settings.readerDecodeSize] (1.5x…3x / origin).
     */
    protected abstract fun onRequest(index: Int, force: Boolean = false, orgImg: Boolean = false)

    /** Source adapters may reprioritize transport/extraction once per navigation update. */
    protected open fun onNavigation(demand: ReaderDemand) = Unit

    fun notifyPageWait(index: Int) {
        pages[index].reset()
    }

    fun notifyPagePercent(index: Int, percent: Float) {
        pages[index].statusFlow.update {
            when (it) {
                is PageStatus.Loading -> it.apply { progress.update { percent } }
                else -> PageStatus.Loading(MutableStateFlow(percent))
            }
        }
    }

    private fun publishPageSucceed(index: Int, image: Image, replaceCache: Boolean) {
        if (replaceCache) {
            lock.write {
                try {
                    val existing = cache.get(index)
                    if (existing !== image) {
                        // Replace any prior entry first so put() doesn't sum two huge weights.
                        if (existing != null) cache.remove(index)
                        // Construction refcnt=1 is the cache ownership; do not pin again.
                        cache.put(index, image)
                    }
                } catch (e: Throwable) {
                    logcat(e)
                }
            }
        }
        pages[index].rememberLayout(image.intrinsicSize.width, image.intrinsicSize.height)
        pages[index].statusFlow.update { if (image.hasQrCode) PageStatus.Blocked(image) else PageStatus.Ready(image) }
    }

    private fun notifyPageSucceed(index: Int, image: Image, replaceCache: Boolean = true) {
        publishPageSucceed(index, image, replaceCache)
        releaseInflight(index)
    }

    /** Validate, publish, and release one decode owner in the same critical section. */
    private fun commitDecodedImage(index: Int, image: Image, runningJob: Job?): Boolean = synchronized(jobs) {
        val demanded = index in desiredDecodedPages || index in forcedDecode
        if (!demanded || runningJob == null || jobs[index] !== runningJob) return@synchronized false
        publishPageSucceed(index, image, replaceCache = true)
        jobs.remove(index)
        inflight.remove(index)
        forcedDecode.remove(index)
        true
    }

    fun notifyPageFailed(index: Int, error: String?) {
        pages[index].statusFlow.update { PageStatus.Error(error) }
        releaseInflight(index)
    }

    override fun close() {
        cancelDecodeJobs()
        lock.write { cache.evictAll() }
        persistProgress()
    }

    override fun persistProgress() {
        debouncePersistJob?.cancel()
        debouncePersistJob = null
        if (info == null) return
        progressScope.launch { persistProgressNow() }
    }

    private var debouncePersistJob: Job? = null
    private val persistMutex = Mutex()

    @Volatile
    private var lastPersistedPage = Int.MIN_VALUE

    private fun schedulePersistProgress() {
        if (info == null) return
        debouncePersistJob?.cancel()
        debouncePersistJob = progressScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistProgressNow()
        }
    }

    private suspend fun persistProgressNow() {
        val gallery = info ?: return
        val page = startPage
        persistMutex.mutexWithLock {
            if (page == lastPersistedPage) return
            runCatching { EhDB.putReadProgress(gallery, page) }
                .onSuccess { lastPersistedPage = page }
        }
    }

    abstract override val title: String

    protected abstract fun getImageExtension(index: Int): String?

    override fun getImageFilename(index: Int): String? = getImageExtension(index)?.let {
        FileUtils.sanitizeFilename("$title - ${index + 1}.${it.lowercase()}")
    }

    private fun requestDecode(index: Int) {
        if (index !in 0 until size) return
        val page = pages.getOrNull(index) ?: return
        when (val st = page.status) {
            is PageStatus.Ready -> {
                // Keep showing a live decode; only reload if bitmap was recycled.
                if (st.image.innerImage != null) {
                    return
                }
            }
            is PageStatus.Blocked -> {
                return
            }
            else -> Unit
        }

        val image = lock.read { cache.get(index) }
        if (image != null && image.innerImage != null) {
            // Re-publish status; same-instance replace is a no-op in notifyPageSucceed.
            notifyPageSucceed(index, image, replaceCache = true)
            return
        }

        // Claim before onRequest so a second call during download/extract (jobs still empty)
        // cannot notifyPageWait + start the same page again.
        val started = claimInflight(index)
        if (!started) return
        // Already Loading: keep progress. Queued/Error: show wait until source is ready.
        if (page.status !is PageStatus.Loading) {
            notifyPageWait(index)
        }
        try {
            onRequest(index)
        } catch (e: Throwable) {
            releaseInflight(index)
            notifyPageFailed(index, e.displayString())
        }
    }

    /**
     * Submit complete viewport truth. This is the only method that changes scheduling direction
     * or creates speculative work; composed [PagerItem]s do not request pages independently.
     */
    @Synchronized
    override fun navigate(navigation: ReaderNavigation) {
        if (size <= 0) return
        val decodeAhead = if (
            Settings.readerAutoDecodeAhead.value && isAutoDecodeAheadFormat(navigation.anchor)
        ) {
            2
        } else {
            Settings.readerDecodeAhead.value.coerceAtLeast(0)
        }
        val policy = ReaderLoadPolicy(
            sourceAhead = Settings.preloadImage.value.coerceAtLeast(0),
            decodeAhead = decodeAhead,
        )
        val demand = demandPlanner.plan(navigation, size, policy)
        lastNavigation = demand.navigation
        desiredDecodedPages = demand.decodedPages
        startPage = demand.navigation.anchor

        onNavigation(demand)
        prioritizeDecode(demand.decodedPages)
        dropUndemandedDecodedPages(demand.decodedPages)
        // Interactive viewport first, then nearest decoded neighbors.
        demand.visibleDecode.forEach(::requestDecode)
        demand.decodeAhead.forEach(::requestDecode)
        prefetchAbsent(demand.sourceOnly)
    }

    /**
     * Ready pages pin bitmaps / animated decoders until status changes. Local zip
     * always extracts to RAM, so this is not cache-off-only. Keep ±1 so webtoon
     * reverse scroll does not collapse item height and fight the same page.
     */
    private fun dropUndemandedDecodedPages(desired: Set<Int>) {
        val n = size
        if (n <= 0) return
        val keepMin = (desired.minOrNull() ?: 0) - 1
        val keepMax = (desired.maxOrNull() ?: 0) + 1
        fun keep(i: Int) = i in desired || i in keepMin..keepMax
        for (i in 0 until n) {
            if (keep(i)) continue
            releaseRamPage(i)
            val page = pages.getOrNull(i) ?: continue
            when (page.status) {
                is PageStatus.Ready, is PageStatus.Blocked, is PageStatus.Loading -> page.reset()
                else -> Unit
            }
        }
        lock.write {
            for (i in 0 until n) {
                if (!keep(i)) cache.remove(i)
            }
        }
    }

    private fun isAutoDecodeAheadFormat(index: Int): Boolean {
        val extension = getImageExtension(index)?.lowercase()?.removePrefix(".") ?: return false
        // JXR uses the native conversion pipeline and is intentionally kept to one page.
        if (extension == "jxr") return true
        // ProXDR is an HEIC trailer format; only apply this to HEIC-family files when
        // the existing ProXDR decoder is enabled.
        return Settings.readerOppoProxdr.value && isHeicImageExtension(extension)
    }

    /** Recompute windows after policy/catalog changes without clearing decoded images. */
    override fun replan() {
        lastNavigation?.let(::navigate)
    }

    /** Retry currently demanded pages after network transports are recreated on resume. */
    override fun onForeground() {
        replan()
    }

    private fun prefetchAbsent(prefetchIndices: List<Int>) {
        if (prefetchIndices.isEmpty()) return
        val pagesAbsent = prefetchIndices.filter {
            it in 0 until size && when (pages[it].status) {
                PageStatus.Queued, is PageStatus.Error -> true
                else -> false
            }
        }
        if (pagesAbsent.isEmpty()) return
        val start = pagesAbsent.min()
        val end = pagesAbsent.max()
        prefetchPages(pagesAbsent, start - 5..end + 5)
    }

    /**
     * Optional cancel of an in-flight decode (e.g. reader close). Prefer not cancelling
     * on pager dispose — let decode finish into memory cache to avoid Queued/no-job races.
     */
    fun cancelRequest(index: Int) {
        val job = synchronized(jobs) {
            inflight.remove(index)
            forcedDecode.remove(index)
            jobs.remove(index)
        }
        job?.cancel()
    }

    abstract override fun save(index: Int, file: Path): Boolean

    /**
     * Decode [index] when the source file is ready.
     * @param orgImg one-shot full-res (page sheet "View original"); otherwise
     *   [Settings.readerDecodeSize] controls Coil target size.
     */
    fun notifySourceReady(index: Int, orgImg: Boolean = false) {
        if (index !in 0 until size) return
        if (!isDecodeDemanded(index)) {
            // A cancelled/old source operation completed after a seek or reversal.
            releaseInflight(index)
            return
        }
        // Already have a live Ready image — skip redundant decode.
        val st = pages.getOrNull(index)?.status
        if (st is PageStatus.Ready && st.image.innerImage != null && !orgImg) {
            releaseInflight(index)
            return
        }
        if (st is PageStatus.Blocked && !orgImg) {
            releaseInflight(index)
            return
        }

        synchronized(jobs) {
            val existing = jobs[index]
            if (existing?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val runningJob = currentCoroutineContext()[Job]
                try {
                    mutex.withLock(index) {
                        semaphore.withPermit {
                            atomicallyDecodeAndUpdate(index, forceOriginal = orgImg)
                        }
                    }
                } catch (e: CancellationException) {
                    // A replacement job may already own this index after a jump/restart.
                    // Never clear its claim or overwrite its Loading/Ready state.
                    if (ownsDecodeSlot(index, runningJob)) {
                        releaseInflight(index)
                        val cur = pages.getOrNull(index)?.status
                        if (cur !is PageStatus.Ready && cur !is PageStatus.Blocked) {
                            notifyPageWait(index)
                        }
                    }
                    throw e
                } catch (e: Throwable) {
                    if (ownsDecodeSlot(index, runningJob)) {
                        notifyPageFailed(index, e.displayString())
                    }
                } finally {
                    synchronized(jobs) {
                        if (jobs[index] === runningJob) {
                            jobs.remove(index)
                            inflight.remove(index)
                            forcedDecode.remove(index)
                        }
                    }
                }
            }
            jobs[index] = job
            job.start()
        }
    }

    abstract fun openSource(index: Int): ImageSource

    /**
     * Cache-off: page-cache primary for this index (`hash.jxl`, `0.jxr`, …).
     * Lib convert writes the Ultra HDR `.jpg` sibling so the next [onRequest]
     * hits disk instead of re-fetching. Null → content-hash derived cache only
     * (local mmap archives).
     */
    protected open fun convertDestPath(index: Int): Path? = null

    /** Drop the compressed RAM copy after a lib still was persisted to disk. */
    protected open fun releaseRamPage(index: Int) {}

    /** True while [index] is in the current viewport + decode-ahead window. */
    protected fun isDecodedDemand(index: Int): Boolean = index in desiredDecodedPages
}
