package com.hippo.ehviewer.library

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Windowed readahead for archive stream I/O (SMB keep-open or WebDAV Range).
 *
 * **No-overlap rules (RAR-like unique bytes):**
 * - Hits copy from the current window or a completed pipeline window.
 * - Partial hits **extend the tail only** — never re-GET bytes already in [win].
 * - Known large [want] (member body) fetches **exactly** that length (no forced 8 MiB floor).
 * - Pipeline prefetches only the strict continuation at `winEnd` (never overlapping need).
 * - [warm] is tail-only / no-op when already covered.
 *
 * Sparse probes (`want` ≤ [RANDOM_WINDOW], non-solid) stay small so ZIP EOCD / local-header
 * probes do not pull multi‑MiB bodies.
 *
 * Cover thumbs use a smaller [sequentialWindow] with [pipeline] off.
 */
class ReadAheadArchiveByteSource(
    private val inner: ArchiveByteSource,
    private val sequentialWindow: Int = SEQUENTIAL_WINDOW,
    private val randomWindow: Int = RANDOM_WINDOW,
    /** When true, forward misses prefer large sequential windows (solid / TAR chunk). */
    private val preferSequential: Boolean = false,
    /** Prefetch the next window after a sequential fill. Off for cover thumbs. */
    private val pipeline: Boolean = true,
) : ArchiveByteSource {
    // java.lang.Object required for wait/notify (monitor API not on kotlin.Any).
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private var winStart: Long = -1L
    private var winLen: Int = 0
    private var win: ByteArray? = null

    private var prefStart: Long = -1L
    private var prefLen: Int = 0
    private var pref: ByteArray? = null
    private var prefFlightOff: Long = -1L
    private var prefFlightEnd: Long = -1L
    private var prefInFlight = false
    private var closed = false
    private val prefEpoch = AtomicInteger(0)

    /** Soft cap on retained window (current + room to extend). */
    private val maxWinBytes: Int = sequentialWindow * 2

    override val size: Long
        get() = runCatching { inner.size }.getOrDefault(-1L)

    override val isRandomAccess: Boolean
        get() = inner.isRandomAccess

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        return try {
            val fileSize = size
            if (fileSize <= 0L) return -1
            if (offset >= fileSize) return 0
            val want = minOf(len.toLong(), fileSize - offset).toInt()

            // Fast path under lock
            synchronized(lock) {
                if (closed) return -1
                if (serveLocked(offset, want, buf, off, fileSize)) return want
            }

            // Wait for overlapping pipeline (no competing GET).
            if (pipeline && awaitOverlappingPrefetch(offset, want, fileSize, buf, off)) {
                return want
            }
            if (closed) return -1

            // Tail-extend when partial hit (network outside lock).
            if (tryExtendTail(offset, want, fileSize)) {
                synchronized(lock) {
                    if (closed) return -1
                    if (serveLocked(offset, want, buf, off, fileSize)) return want
                }
            }

            if (pipeline && awaitOverlappingPrefetch(offset, want, fileSize, buf, off)) {
                return want
            }

            val fetch = chooseFetch(offset, want, fileSize)
            fillWindowSync(offset, fetch, want, buf, off, fileSize)
        } catch (e: RemoteRangeNotSupportedException) {
            throw e
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Prefill at [offset] up to [length] (capped at [sequentialWindow]).
     * Tail-only when partially covered; no-op when fully covered.
     */
    override fun warm(offset: Long, length: Int) {
        if (offset < 0L || length <= 0) return
        try {
            val fileSize = size
            if (fileSize <= 0L || offset >= fileSize) return
            val want = minOf(
                length.toLong(),
                sequentialWindow.toLong(),
                fileSize - offset,
            ).toInt()
            if (want <= 0) return

            synchronized(lock) {
                if (closed) return
                if (rangeFullyInWinLocked(offset, want)) return
                if (tryPromotePrefetchLocked(offset, want)) return
            }
            if (pipeline && awaitOverlappingPrefetch(offset, want, fileSize)) return
            if (closed) return
            if (tryExtendTail(offset, want, fileSize)) {
                synchronized(lock) {
                    if (rangeFullyInWinLocked(offset, want)) return
                }
            }
            synchronized(lock) {
                if (closed) return
                if (rangeFullyInWinLocked(offset, want)) return
                if (tryPromotePrefetchLocked(offset, want)) return
            }
            fillWindowSync(offset, want, 0, null, 0, fileSize)
        } catch (_: Throwable) {
            // Network blip during warm — ignore.
        }
    }

    /** Caller holds [lock]. */
    private fun serveLocked(
        offset: Long,
        want: Int,
        buf: ByteArray?,
        off: Int,
        fileSize: Long,
    ): Boolean {
        if (copyFromWinLocked(offset, want, buf, off)) {
            maybeKickPrefetchLocked(fileSize)
            return true
        }
        if (tryPromotePrefetchLocked(offset, want)) {
            if (buf != null) {
                System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
            }
            maybeKickPrefetchLocked(fileSize)
            return true
        }
        return false
    }

    private fun awaitOverlappingPrefetch(
        offset: Long,
        want: Int,
        fileSize: Long,
        copyBuf: ByteArray? = null,
        copyOff: Int = 0,
    ): Boolean {
        while (true) {
            val wait: Boolean
            synchronized(lock) {
                if (closed) return false
                if (serveLocked(offset, want, copyBuf, copyOff, fileSize)) return true
                wait = prefInFlight && (
                    rangeFullyInsideFlightLocked(offset, want) ||
                        rangeOverlapsFlightLocked(offset, want)
                    )
                if (wait) {
                    try {
                        lock.wait(50L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            if (!wait) {
                synchronized(lock) {
                    if (serveLocked(offset, want, copyBuf, copyOff, fileSize)) return true
                }
                return false
            }
        }
    }

    /**
     * If [offset] is inside the current window (or at winEnd) and need spills past winEnd,
     * network-fetch **only** the missing tail and append. Never re-GETs in-window bytes.
     * Network runs **outside** [lock].
     */
    private fun tryExtendTail(offset: Long, want: Int, fileSize: Long): Boolean {
        var fetchOff = -1L
        var fetch = 0
        val ready = synchronized(lock) {
            if (closed) return false
            if (win == null || winLen <= 0 || winStart < 0L) return false
            val winEnd = winStart + winLen
            val needEnd = offset + want
            if (offset < winStart || offset > winEnd) return false
            if (needEnd <= winEnd) return true

            // Absorb completed pipeline that continues us (no network).
            if (pref != null && prefStart == winEnd && prefLen > 0) {
                appendBytesLocked(pref!!, prefLen)
                pref = null
                prefLen = 0
                prefStart = -1L
                if (winStart + winLen >= needEnd) return true
            }

            val curEnd = winStart + winLen
            if (curEnd >= needEnd) return true
            if (curEnd >= fileSize) return false

            // In-flight exact continuation — let await handle it.
            if (prefInFlight && prefFlightOff == curEnd) return false

            val missing = (needEnd - curEnd).toInt().coerceAtLeast(1)
            val pad = if (preferSequential && want <= randomWindow) {
                minOf(sequentialWindow.toLong(), fileSize - curEnd).toInt()
            } else {
                minOf(missing.toLong(), fileSize - curEnd).toInt()
            }
            fetch = maxOf(missing, pad).coerceAtMost((fileSize - curEnd).toInt())
            if (fetch <= 0) return false
            fetchOff = curEnd
            true // need network
        }
        if (!ready || fetchOff < 0L || fetch <= 0) return false

        val tail = ByteArray(fetch)
        val got = try {
            inner.readAt(fetchOff, tail, 0, fetch)
        } catch (e: RemoteRangeNotSupportedException) {
            throw e
        } catch (_: Throwable) {
            -1
        }
        if (got <= 0) return false

        synchronized(lock) {
            if (closed) return false
            // Only append if window still ends where we planned (no race replace).
            if (win == null || winStart + winLen != fetchOff) {
                return rangeFullyInWinLocked(offset, want)
            }
            appendBytesLocked(tail, got)
            val newEnd = winStart + winLen
            if (pref != null && prefStart != newEnd) {
                pref = null
                prefLen = 0
                prefStart = -1L
            }
            maybeKickPrefetchLocked(fileSize)
            return rangeFullyInWinLocked(offset, want)
        }
    }

    private fun copyFromWinLocked(
        offset: Long,
        want: Int,
        copyBuf: ByteArray?,
        copyOff: Int,
    ): Boolean {
        val w = win ?: return false
        if (offset < winStart || offset + want > winStart + winLen) return false
        if (copyBuf != null) {
            System.arraycopy(w, (offset - winStart).toInt(), copyBuf, copyOff, want)
        }
        return true
    }

    private fun rangeFullyInWinLocked(offset: Long, want: Int): Boolean {
        if (win == null) return false
        return offset >= winStart && offset + want <= winStart + winLen
    }

    private fun rangeFullyInsideFlightLocked(offset: Long, want: Int): Boolean {
        if (!prefInFlight || prefFlightOff < 0L || prefFlightEnd <= prefFlightOff) return false
        return offset >= prefFlightOff && offset + want <= prefFlightEnd
    }

    private fun rangeOverlapsFlightLocked(offset: Long, want: Int): Boolean {
        if (!prefInFlight || prefFlightOff < 0L || prefFlightEnd <= prefFlightOff) return false
        val end = offset + want
        return offset < prefFlightEnd && end > prefFlightOff
    }

    /** Caller holds [lock]. Append [len] bytes; slide front if over [maxWinBytes]. */
    private fun appendBytesLocked(src: ByteArray, len: Int) {
        val w = win ?: return
        if (len <= 0) return
        val newLen = winLen + len
        if (newLen <= maxWinBytes) {
            val grown = if (w.size >= newLen) {
                w
            } else {
                ByteArray(newLen.coerceAtLeast(minOf(sequentialWindow, maxWinBytes)))
            }
            if (grown !== w) {
                System.arraycopy(w, 0, grown, 0, winLen)
            }
            System.arraycopy(src, 0, grown, winLen, len)
            win = grown
            winLen = newLen
            return
        }
        val combinedLen = newLen
        val keep = maxWinBytes
        val drop = combinedLen - keep
        val slid = ByteArray(keep)
        // Copy kept prefix of old window
        val keepFromOld = (winLen - drop).coerceAtLeast(0)
        if (keepFromOld > 0) {
            System.arraycopy(w, drop, slid, 0, keepFromOld)
        }
        // Rest from src
        val srcSkip = (drop - winLen).coerceAtLeast(0)
        val fromSrc = keep - keepFromOld
        System.arraycopy(src, srcSkip, slid, keepFromOld, fromSrc)
        win = slid
        winStart += drop.toLong()
        winLen = keep
    }

    /**
     * Bytes to fetch on a full miss (disjoint from window).
     * Large [want] → exact size; sparse → random; sequential continue → window.
     */
    private fun chooseFetch(offset: Long, want: Int, fileSize: Long): Int {
        val remaining = (fileSize - offset).coerceAtLeast(0L)
        if (remaining <= 0L) return 0

        if (!preferSequential && want > 0 && want <= randomWindow) {
            return minOf(randomWindow.toLong(), remaining).toInt()
        }

        // Known large member body: exact (no 8 MiB floor).
        if (want > randomWindow) {
            return minOf(want.toLong(), remaining).toInt()
        }

        synchronized(lock) {
            if (win == null) {
                val base = if (offset == 0L || preferSequential) sequentialWindow else randomWindow
                return minOf(maxOf(base, want).toLong(), remaining).toInt()
            }
            val end = winStart + winLen
            return when {
                offset == end ->
                    minOf(sequentialWindow.toLong(), remaining).toInt().coerceAtLeast(want)
                offset > end && offset - end <= sequentialWindow ->
                    minOf(sequentialWindow.toLong(), remaining).toInt().coerceAtLeast(want)
                offset >= winStart && offset < end ->
                    minOf(want.toLong(), remaining).toInt()
                offset < winStart ->
                    minOf(randomWindow.toLong(), remaining).toInt().coerceAtLeast(want)
                preferSequential ->
                    minOf(sequentialWindow.toLong(), remaining).toInt().coerceAtLeast(want)
                else ->
                    minOf(randomWindow.toLong(), remaining).toInt().coerceAtLeast(want)
            }
        }
    }

    private fun fillWindowSync(
        offset: Long,
        fetch: Int,
        copyLen: Int,
        copyBuf: ByteArray?,
        copyOff: Int,
        fileSize: Long = size,
    ): Int {
        if (fetch <= 0) return if (copyLen > 0) -1 else 0
        val need = if (copyLen > 0) copyLen else fetch

        synchronized(lock) {
            if (closed) return -1
            if (serveLocked(offset, need.coerceAtLeast(1), copyBuf, copyOff, fileSize) &&
                (copyLen <= 0 || copyFromWinLocked(offset, copyLen, copyBuf, copyOff))
            ) {
                return if (copyLen > 0) copyLen else winLen
            }
        }

        // Do not double-GET a range the pipeline already owns.
        if (pipeline && rangesOverlapFetch(offset, fetch)) {
            if (awaitOverlappingPrefetch(offset, need.coerceAtLeast(1), fileSize, copyBuf, copyOff)) {
                return if (copyLen > 0) copyLen else need
            }
        }

        val fresh = ByteArray(fetch)
        val got = try {
            inner.readAt(offset, fresh, 0, fetch)
        } catch (e: RemoteRangeNotSupportedException) {
            throw e
        } catch (_: Throwable) {
            -1
        }

        synchronized(lock) {
            if (closed) return -1
            if (got <= 0) {
                winStart = -1L
                winLen = 0
                win = null
                return got
            }
            if (win != null && offset == winStart + winLen) {
                appendBytesLocked(fresh, got)
            } else {
                win = if (got == fresh.size) fresh else fresh.copyOf(got)
                winStart = offset
                winLen = got
            }
            val newEnd = winStart + winLen
            if (pref != null && prefStart != newEnd) {
                pref = null
                prefLen = 0
                prefStart = -1L
            }
            maybeKickPrefetchLocked(fileSize)
            if (copyBuf != null && copyLen > 0) {
                if (copyFromWinLocked(offset, copyLen, copyBuf, copyOff)) return copyLen
                val n = minOf(copyLen, got)
                System.arraycopy(fresh, 0, copyBuf, copyOff, n)
                return n
            }
            return got
        }
    }

    private fun rangesOverlapFetch(offset: Long, fetch: Int): Boolean {
        synchronized(lock) {
            if (!prefInFlight) return false
            val end = offset + fetch
            return offset < prefFlightEnd && end > prefFlightOff
        }
    }

    /** Caller holds [lock]. */
    private fun tryPromotePrefetchLocked(offset: Long, want: Int): Boolean {
        val p = pref ?: return false
        if (want <= 0) return false
        if (offset >= prefStart && offset + want <= prefStart + prefLen) {
            win = p
            winStart = prefStart
            winLen = prefLen
            pref = null
            prefLen = 0
            prefStart = -1L
            return true
        }
        // Pref continues win and covers the miss after append.
        if (win != null && prefStart == winStart + winLen &&
            offset >= winStart && offset + want <= prefStart + prefLen
        ) {
            appendBytesLocked(p, prefLen)
            pref = null
            prefLen = 0
            prefStart = -1L
            return rangeFullyInWinLocked(offset, want)
        }
        return false
    }

    /** Pipeline only strict continuation at winEnd. Caller holds [lock]. */
    private fun maybeKickPrefetchLocked(fileSize: Long = size) {
        if (!pipeline || closed) return
        if (win == null || winLen <= 0) return
        if (fileSize <= 0L) return
        val nextOff = winStart + winLen
        if (nextOff >= fileSize) return
        if (prefInFlight) return
        if (pref != null && prefStart == nextOff) return
        if (winLen < randomWindow * 2 && !preferSequential) return

        val fetch = minOf(sequentialWindow.toLong(), fileSize - nextOff).toInt()
        if (fetch <= 0) return
        prefInFlight = true
        prefFlightOff = nextOff
        prefFlightEnd = nextOff + fetch
        val epoch = prefEpoch.incrementAndGet()
        PREFETCH_EXECUTOR.execute {
            try {
                if (closed || epoch != prefEpoch.get()) {
                    clearPrefetchFlight(nextOff)
                    return@execute
                }
                synchronized(lock) {
                    // Main path already paid past this point — abort without network.
                    if (win != null && winStart + winLen > nextOff) {
                        clearPrefetchFlightLocked(nextOff)
                        return@execute
                    }
                }
                val buf = ByteArray(fetch)
                val got = inner.readAt(nextOff, buf, 0, fetch)
                synchronized(lock) {
                    if (closed || epoch != prefEpoch.get()) {
                        clearPrefetchFlightLocked(nextOff)
                        return@synchronized
                    }
                    if (win != null && winStart + winLen >= nextOff + got.coerceAtLeast(0) && got > 0) {
                        // Already covered by concurrent extend — drop duplicate bytes.
                        prefInFlight = false
                        prefFlightOff = -1L
                        prefFlightEnd = -1L
                        lock.notifyAll()
                        return@synchronized
                    }
                    if (got > 0) {
                        if (win != null && winStart + winLen == nextOff) {
                            appendBytesLocked(buf, got)
                            pref = null
                            prefStart = -1L
                            prefLen = 0
                        } else {
                            pref = buf
                            prefStart = nextOff
                            prefLen = got
                        }
                    } else {
                        pref = null
                        prefStart = -1L
                        prefLen = 0
                    }
                    prefInFlight = false
                    prefFlightOff = -1L
                    prefFlightEnd = -1L
                    lock.notifyAll()
                }
            } catch (_: Throwable) {
                synchronized(lock) {
                    prefInFlight = false
                    prefFlightOff = -1L
                    prefFlightEnd = -1L
                    pref = null
                    prefStart = -1L
                    prefLen = 0
                    lock.notifyAll()
                }
            }
        }
    }

    private fun clearPrefetchFlight(flightOff: Long) {
        synchronized(lock) {
            clearPrefetchFlightLocked(flightOff)
        }
    }

    private fun clearPrefetchFlightLocked(flightOff: Long) {
        if (prefInFlight && prefFlightOff == flightOff) {
            prefInFlight = false
            prefFlightOff = -1L
            prefFlightEnd = -1L
        }
        lock.notifyAll()
    }

    override fun dropQueuedReads() = inner.dropQueuedReads()

    override fun close() {
        synchronized(lock) {
            closed = true
            prefEpoch.incrementAndGet()
            win = null
            winStart = -1L
            winLen = 0
            pref = null
            prefStart = -1L
            prefLen = 0
            prefInFlight = false
            prefFlightOff = -1L
            prefFlightEnd = -1L
            lock.notifyAll()
        }
        inner.close()
    }

    companion object {
        const val SEQUENTIAL_WINDOW = 8 * 1024 * 1024
        const val COVER_WINDOW = 2 * 1024 * 1024
        const val RANDOM_WINDOW = 64 * 1024

        /**
         * Bounded pipeline prefetch. Was [java.util.concurrent.Executors.newCachedThreadPool]
         * (unbounded grow) — many concurrent archive streams could spawn a thread each.
         * One in-flight prefetch per source; shared pool caps process-wide fan-out.
         */
        private val PREFETCH_EXECUTOR = ThreadPoolExecutor(
            /* core */
            2,
            /* max */
            8,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(16),
            { r -> Thread(r, "archive-readahead").apply { isDaemon = true } },
            // Drop oldest speculative work rather than spawn more threads.
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    }
}
