package com.hippo.ehviewer.gallery

import android.os.Handler
import android.os.Looper
import androidx.collection.SieveCache
import androidx.collection.mutableIntObjectMapOf
import androidx.compose.runtime.mutableIntStateOf
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.bracketCase
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.withNonCancellableContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import com.hippo.ehviewer.util.detectAds
import com.hippo.ehviewer.util.displayString
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.coroutines.NamedMutex
import moe.tarsin.coroutines.withLock
import okio.Path

private val progressScope = CoroutineScope(Dispatchers.IO)

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
    // ~35% of heap for retained pages; leave room for peak decode + UI + Coil thumbs.
    val target = (heap * 35 / 100).toInt()
    val min = (24L * 1024 * 1024).toInt()
    val max = minOf((160L * 1024 * 1024).toInt(), (heap * 45 / 100).toInt())
    return target.coerceIn(min, max.coerceAtLeast(min))
}

abstract class PageLoader(
    val scope: CoroutineScope,
    val info: GalleryInfo?,
    startPage: Int,
    initialSize: Int,
    val hasAds: Boolean = false,
) : AutoCloseable {
    /**
     * Page count. Snapshot-backed so Compose pager/list recompose when solid extract
     * grows the lazy list. [growTo] publishes on the main thread.
     */
    private val sizeState = mutableIntStateOf(initialSize.coerceAtLeast(0))

    /** Observable page count (Compose Snapshot). Seek bar / pager must read this. */
    val size: Int
        get() = sizeState.intValue

    var startPage = if (size <= 0) 0 else startPage.coerceIn(0, size - 1)

    private val jobs = mutableIntObjectMapOf<Job>()
    private val mutex = NamedMutex<Int>()
    /** Peak software decode is large; keep concurrency low on a 256 MiB heap. */
    private val semaphore = Semaphore(4)

    /**
     * Decoded-page budget. Each [sizeOf] entry **must be ≤ maxSize** — androidx
     * [SieveCache.put] then [trimToSize] crashes with
     * `ArrayIndexOutOfBoundsException: index=2147483647` (NodeInvalidLink) when a
     * single bitmap is larger than maxSize (e.g. 7000×5000 original ≈ 133 MiB on a
     * ~90 MiB cache). Clamp weight to [imageCacheMaxBytes].
     */
    private val imageCacheMaxBytes = pageImageCacheMaxBytes()

    private val cache = SieveCache<Int, Image>(
        maxSize = imageCacheMaxBytes,
        sizeOf = { _, v -> cacheWeightOf(v) },
        // Only drop the cache ref. Do NOT notifyPageWait here — that forced Queued while a
        // still-composed page might have no active decode job (forever spinner). Reload is
        // driven by request() when the page is shown / pin fails.
        onEntryRemoved = { _, o, _, _ -> o.unpin() },
    )

    private fun cacheWeightOf(image: Image): Int {
        val raw = image.allocationSize
        if (raw <= 0L) return 1
        // Never report more than maxSize — SieveCache cannot evict a sole oversize entry.
        return raw.coerceAtMost(imageCacheMaxBytes.toLong()).toInt().coerceAtLeast(1)
    }

    private suspend fun atomicallyDecodeAndUpdate(index: Int, forceOriginal: Boolean) {
        bracketCase(
            { openSource(index) },
            { src ->
                withNonCancellableContext {
                    notifyPageSucceed(
                        index,
                        Image.decode(
                            src,
                            checkExtraneousAds = hasAds && detectAds(index, size),
                            forceOriginal = forceOriginal,
                        ),
                    )
                }
            },
            { src, case -> if (case !is ExitCase.Completed) src.close() },
        )
    }

    private val lock = ReentrantReadWriteLock()

    private val pageList = ArrayList<Page>(initialSize.coerceAtLeast(1)).apply {
        repeat(initialSize.coerceAtLeast(0)) { add(Page(it)) }
    }

    /** Live page slots; grows with [growTo] for solid lazy lists. */
    val pages: List<Page> get() = pageList

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
            if (n > sizeState.intValue) sizeState.intValue = n
        } else {
            pageLoaderMainHandler.post {
                if (n > sizeState.intValue) sizeState.intValue = n
            }
        }
    }

    private val prefetchPageCount = Settings.preloadImage.value

    fun restart() {
        lock.write { cache.evictAll() }
        pages.forEach(Page::reset)
    }

    private val prevIndex = AtomicInt(-1)

    fun retryPage(index: Int, orgImg: Boolean = false) {
        cancelRequest(index)
        notifyPageWait(index)
        lock.write { cache.remove(index) }
        onRequest(index, true, orgImg)
    }

    protected abstract fun prefetchPages(pages: List<Int>, bounds: IntRange)

    /**
     * @param orgImg if true, force full-resolution decode for this page (page menu).
     *   Otherwise uses [Settings.readerDecodeSize] (1.5x…3x / origin).
     */
    protected abstract fun onRequest(index: Int, force: Boolean = false, orgImg: Boolean = false)

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

    fun notifyPageSucceed(index: Int, image: Image, replaceCache: Boolean = true) {
        if (replaceCache) {
            lock.write {
                // Replace any prior entry first so put() doesn't sum two huge weights.
                cache.remove(index)
                // sizeOf is clamped to maxSize — safe for SieveCache put/trim.
                cache[index] = image
            }
        }
        pages[index].statusFlow.update { if (image.hasQrCode) PageStatus.Blocked(image) else PageStatus.Ready(image) }
    }

    fun notifyPageFailed(index: Int, error: String?) {
        pages[index].statusFlow.update { PageStatus.Error(error) }
    }

    override fun close() {
        lock.write { cache.evictAll() }
        info?.let { gallery ->
            progressScope.launch {
                // Ensure GALLERIES row exists — Progress has FK to GALLERIES
                runCatching { EhDB.putReadProgress(gallery, startPage) }
            }
        }
    }

    abstract val title: String

    protected abstract fun getImageExtension(index: Int): String?

    fun getImageFilename(index: Int): String? = getImageExtension(index)?.let {
        FileUtils.sanitizeFilename("$title - ${index + 1}.${it.lowercase()}")
    }

    fun request(index: Int) {
        if (index !in 0 until size) return
        val prefetchRange = if (index >= prevIndex.load()) {
            index + 1..(index + prefetchPageCount).coerceAtMost(size - 1)
        } else {
            index - 1 downTo (index - prefetchPageCount).coerceAtLeast(0)
        }
        prevIndex.store(index)

        val page = pages.getOrNull(index) ?: return
        when (val st = page.status) {
            is PageStatus.Ready -> {
                // Keep showing a live decode; only reload if bitmap was recycled.
                if (st.image.innerImage != null) {
                    prefetchAbsent(prefetchRange)
                    return
                }
            }
            is PageStatus.Blocked -> {
                prefetchAbsent(prefetchRange)
                return
            }
            else -> Unit
        }

        val image = lock.read { cache[index] }
        if (image != null && image.innerImage != null) {
            // Re-insert so cache stays coherent after partial eviction.
            notifyPageSucceed(index, image, replaceCache = true)
            prefetchAbsent(prefetchRange)
            return
        }

        // Already decoding this page — do not reset to Queued (that caused forever spinners
        // when a second request no-op'd notifySourceReady while wiping Ready/progress).
        val decoding = synchronized(jobs) { jobs[index]?.isActive == true }
        if (decoding) {
            prefetchAbsent(prefetchRange)
            return
        }

        notifyPageWait(index)
        onRequest(index)
        prefetchAbsent(prefetchRange)
    }

    private fun prefetchAbsent(prefetchRange: IntProgression) {
        if (prefetchRange.isEmpty()) return
        val pagesAbsent = prefetchRange.filter {
            it in 0 until size && when (pages[it].status) {
                PageStatus.Queued, is PageStatus.Error -> true
                else -> false
            }
        }
        if (pagesAbsent.isEmpty()) return
        val start = if (prefetchRange.step > 0) prefetchRange.first else prefetchRange.last
        val end = if (prefetchRange.step > 0) prefetchRange.last else prefetchRange.first
        prefetchPages(pagesAbsent, start - 5..end + 5)
    }

    /**
     * Optional cancel of an in-flight decode (e.g. reader close). Prefer not cancelling
     * on pager dispose — let decode finish into memory cache to avoid Queued/no-job races.
     */
    fun cancelRequest(index: Int) {
        val job = synchronized(jobs) { jobs.remove(index) }
        job?.cancel()
    }

    abstract fun save(index: Int, file: Path): Boolean

    /**
     * Decode [index] when the source file is ready.
     * @param orgImg one-shot full-res (page sheet "View original"); otherwise
     *   [Settings.readerDecodeSize] controls Coil target size.
     */
    fun notifySourceReady(index: Int, orgImg: Boolean = false) {
        if (index !in 0 until size) return
        // Already have a live Ready image — skip redundant decode.
        val st = pages.getOrNull(index)?.status
        if (st is PageStatus.Ready && st.image.innerImage != null && !orgImg) return
        if (st is PageStatus.Blocked && !orgImg) return

        synchronized(jobs) {
            val existing = jobs[index]
            if (existing?.isActive == true) return
            val job = scope.launch {
                try {
                    mutex.withLock(index) {
                        semaphore.withPermit {
                            atomicallyDecodeAndUpdate(index, forceOriginal = orgImg)
                        }
                    }
                } catch (e: CancellationException) {
                    // Do not force Queued/Error — page may still be visible; a later
                    // request()/notifySourceReady will start a fresh job.
                    throw e
                } catch (e: Throwable) {
                    notifyPageFailed(index, e.displayString())
                } finally {
                    synchronized(jobs) {
                        if (jobs[index] === coroutineContext[Job]) {
                            jobs.remove(index)
                        }
                    }
                }
            }
            jobs[index] = job
        }
    }

    abstract fun openSource(index: Int): ImageSource
}
