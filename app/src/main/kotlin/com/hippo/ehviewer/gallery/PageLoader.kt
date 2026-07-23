package com.hippo.ehviewer.gallery

import androidx.collection.SieveCache
import androidx.collection.mutableIntObjectMapOf
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

abstract class PageLoader(val scope: CoroutineScope, val info: GalleryInfo?, startPage: Int, val size: Int, val hasAds: Boolean = false) : AutoCloseable {
    var startPage = startPage.coerceIn(0, size - 1)

    private val jobs = mutableIntObjectMapOf<Job>()
    private val mutex = NamedMutex<Int>()
    /** Peak software decode is large; keep concurrency low on a 256 MiB heap. */
    private val semaphore = Semaphore(4)

    private val cache = SieveCache<Int, Image>(
        maxSize = pageImageCacheMaxBytes(),
        sizeOf = { _, v -> v.allocationSize.toInt().coerceAtLeast(1) },
        onEntryRemoved = { k, o, n, _ -> if (o.unpin()) n ?: notifyPageWait(k) },
    )

    private suspend fun atomicallyDecodeAndUpdate(index: Int) {
        bracketCase(
            { openSource(index) },
            { src ->
                withNonCancellableContext {
                    notifyPageSucceed(index, Image.decode(src, hasAds && detectAds(index, size)))
                }
            },
            { src, case -> if (case !is ExitCase.Completed) src.close() },
        )
    }

    private val lock = ReentrantReadWriteLock()

    val pages = (0 until size).map { Page(it) }

    private val prefetchPageCount = Settings.preloadImage.value

    fun restart() {
        lock.write { cache.evictAll() }
        pages.forEach(Page::reset)
    }

    private val prevIndex = AtomicInt(-1)

    fun retryPage(index: Int, orgImg: Boolean = false) {
        notifyPageWait(index)
        lock.write { cache.remove(index) }
        onRequest(index, true, orgImg)
    }

    protected abstract fun prefetchPages(pages: List<Int>, bounds: IntRange)

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
        if (replaceCache) lock.write { cache[index] = image }
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
        val prefetchRange = if (index >= prevIndex.load()) {
            index + 1..(index + prefetchPageCount).coerceAtMost(size - 1)
        } else {
            index - 1 downTo (index - prefetchPageCount).coerceAtLeast(0)
        }
        prevIndex.store(index)
        val image = lock.read { cache[index] }
        if (image != null) {
            notifyPageSucceed(index, image, false)
        } else {
            notifyPageWait(index)
            onRequest(index)
        }

        // Prefetch to disk
        val pagesAbsent = prefetchRange.filter {
            when (pages[it].status) {
                PageStatus.Queued, is PageStatus.Error -> true
                else -> false
            }
        }
        val start = if (prefetchRange.step > 0) prefetchRange.first else prefetchRange.last
        val end = if (prefetchRange.step > 0) prefetchRange.last else prefetchRange.first
        prefetchPages(pagesAbsent, start - 5..end + 5)
    }

    fun cancelRequest(index: Int) {
        jobs[index]?.cancel()
    }

    abstract fun save(index: Int, file: Path): Boolean

    fun notifySourceReady(index: Int) = synchronized(jobs) {
        if (jobs[index]?.isActive != true) {
            jobs[index] = scope.launch {
                try {
                    mutex.withLock(index) {
                        semaphore.withPermit {
                            atomicallyDecodeAndUpdate(index)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        notifyPageFailed(index, null)
                        throw e
                    } else {
                        notifyPageFailed(index, e.displayString())
                    }
                }
            }
        }
    }

    abstract fun openSource(index: Int): ImageSource
}
