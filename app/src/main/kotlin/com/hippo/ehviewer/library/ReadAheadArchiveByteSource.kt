package com.hippo.ehviewer.library

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Windowed readahead for archive stream I/O (SMB keep-open or WebDAV Range).
 *
 * - **Sequential** (forward continuation / start-of-file) → large fetch
 * - **Random** (backward or far jump) → small fetch (EOCD tail, ZIP local headers)
 * - **Pipeline**: after a sequential fill, prefetch the **next** window on a worker so
 *   decompress / page-write can overlap network. Without this, solid extract shows
 *   sawtooth traffic (burst → idle → burst), especially on SMB where each range has
 *   higher startup cost than a warm WebDAV GET stream.
 * - **[warm]** explicit next-page fill (up to [SEQUENTIAL_WINDOW])
 *
 * Blind large readahead on every miss re-downloads zip members during header walks;
 * ZIP open uses CD-only indexing; solid / page extract use the sequential path.
 */
class ReadAheadArchiveByteSource(
    private val inner: ArchiveByteSource,
    private val sequentialWindow: Int = SEQUENTIAL_WINDOW,
    private val randomWindow: Int = RANDOM_WINDOW,
    /** When true, forward misses always use the large window (solid fake-stream). */
    private val preferSequential: Boolean = false,
    /** Overlap next-window network with current-window consumption. */
    private val pipeline: Boolean = true,
) : ArchiveByteSource {
    private val lock = Any()
    private var winStart: Long = -1L
    private var winLen: Int = 0
    private var win: ByteArray? = null

    private var prefStart: Long = -1L
    private var prefLen: Int = 0
    private var pref: ByteArray? = null
    /** Target offset of an in-flight prefetch (valid while [prefInFlight]). */
    private var prefFlightOff: Long = -1L
    private var prefInFlight = false
    private var closed = false
    private val prefEpoch = AtomicInteger(0)

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (offset >= size) return 0
        val want = minOf(len.toLong(), size - offset).toInt()

        // Fast path: current window hit (no network).
        synchronized(lock) {
            if (closed) return -1
            win?.let { w ->
                if (offset >= winStart && offset + want <= winStart + winLen) {
                    System.arraycopy(w, (offset - winStart).toInt(), buf, off, want)
                    maybeKickPrefetchLocked()
                    return want
                }
            }
            // Promote completed prefetch if it covers this read.
            if (tryPromotePrefetchLocked(offset, want)) {
                System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
                maybeKickPrefetchLocked()
                return want
            }
        }

        // Wait if the next window is already being fetched for this offset
        // (avoid a second SMB/WebDAV range for the same 8 MiB).
        if (pipeline) {
            val deadline = System.nanoTime() + PREFETCH_WAIT_NS
            while (System.nanoTime() < deadline) {
                val wait: Boolean
                synchronized(lock) {
                    if (closed) return -1
                    if (tryPromotePrefetchLocked(offset, want)) {
                        System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
                        maybeKickPrefetchLocked()
                        return want
                    }
                    wait = prefInFlight && prefFlightOff == offset
                    if (wait) {
                        val remainingMs =
                            ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                        try {
                            (lock as Object).wait(remainingMs.coerceAtMost(50L))
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                if (!wait) break
            }
            synchronized(lock) {
                if (tryPromotePrefetchLocked(offset, want)) {
                    System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
                    maybeKickPrefetchLocked()
                    return want
                }
            }
        }

        val window = chooseWindow(offset)
        val fetch = minOf(window.toLong(), size - offset).toInt().coerceAtLeast(want)
        return fillWindowSync(offset, fetch, want, buf, off)
    }

    /**
     * Prefill readahead at [offset] (e.g. next page local-header / data).
     * Fetches up to min([length], [sequentialWindow]) so multi‑MiB pages can land
     * in one network round-trip when the link is warm.
     */
    override fun warm(offset: Long, length: Int) {
        if (offset < 0L || length <= 0) return
        if (offset >= size) return
        val want = minOf(
            length.toLong(),
            sequentialWindow.toLong(),
            size - offset,
        ).toInt()
        if (want <= 0) return
        synchronized(lock) {
            if (closed) return
            if (win != null && offset >= winStart && offset + want <= winStart + winLen) {
                return
            }
            if (tryPromotePrefetchLocked(offset, want)) {
                return
            }
        }
        fillWindowSync(offset, want, 0, null, 0)
    }

    /**
     * Pick fetch size. Start-of-file and forward continuation use the large window so
     * solid sequential extract does not thrash on 64 KiB random windows between members.
     */
    private fun chooseWindow(offset: Long): Int {
        synchronized(lock) {
            if (win == null) {
                // First open is almost always sequential from 0 (solid) or a deliberate seek.
                return if (offset == 0L || preferSequential) sequentialWindow else randomWindow
            }
            val end = winStart + winLen
            return when {
                offset == end -> sequentialWindow
                // Small forward gap (header skip / align) — keep large window.
                offset > end && offset - end <= sequentialWindow -> sequentialWindow
                // Still inside old window but want spilled past end: sequential extension.
                offset >= winStart && offset < end -> sequentialWindow
                // Backward seek
                offset < winStart -> randomWindow
                // Far forward jump
                preferSequential -> sequentialWindow
                else -> randomWindow
            }
        }
    }

    private fun fillWindowSync(
        offset: Long,
        fetch: Int,
        copyLen: Int,
        copyBuf: ByteArray?,
        copyOff: Int,
    ): Int {
        val fresh = ByteArray(fetch)
        val got = inner.readAt(offset, fresh, 0, fetch)
        synchronized(lock) {
            if (closed) return -1
            if (got <= 0) {
                winStart = -1L
                winLen = 0
                win = null
                return got
            }
            win = fresh
            winStart = offset
            winLen = got
            // Invalidate stale prefetch that does not continue this window.
            if (pref != null && prefStart != offset + got) {
                pref = null
                prefLen = 0
                prefStart = -1L
            }
            maybeKickPrefetchLocked()
            if (copyBuf != null && copyLen > 0) {
                val n = minOf(copyLen, got)
                System.arraycopy(fresh, 0, copyBuf, copyOff, n)
                return n
            }
            return got
        }
    }

    /** Caller holds [lock]. */
    private fun tryPromotePrefetchLocked(offset: Long, want: Int): Boolean {
        val p = pref ?: return false
        if (offset < prefStart || offset + want > prefStart + prefLen) return false
        win = p
        winStart = prefStart
        winLen = prefLen
        pref = null
        prefLen = 0
        prefStart = -1L
        return true
    }

    /**
     * If current window is sequential and next bytes are not prefetched yet, fetch them
     * on a background worker (overlaps solid decompress / disk write).
     * Caller holds [lock].
     */
    private fun maybeKickPrefetchLocked() {
        if (!pipeline || closed) return
        if (win == null || winLen <= 0) return
        val nextOff = winStart + winLen
        if (nextOff >= size) return
        if (prefInFlight) return
        if (pref != null && prefStart == nextOff) return
        // Only pipeline when we are consuming a full-size sequential window (not tiny random).
        if (winLen < randomWindow * 2 && !preferSequential) return

        prefInFlight = true
        prefFlightOff = nextOff
        val epoch = prefEpoch.incrementAndGet()
        val fetch = minOf(sequentialWindow.toLong(), size - nextOff).toInt()
        PREFETCH_EXECUTOR.execute {
            try {
                if (closed || epoch != prefEpoch.get()) return@execute
                val buf = ByteArray(fetch)
                val got = inner.readAt(nextOff, buf, 0, fetch)
                synchronized(lock) {
                    if (closed || epoch != prefEpoch.get()) {
                        prefInFlight = false
                        prefFlightOff = -1L
                        (lock as Object).notifyAll()
                        return@synchronized
                    }
                    if (got > 0) {
                        pref = buf
                        prefStart = nextOff
                        prefLen = got
                    } else {
                        pref = null
                        prefStart = -1L
                        prefLen = 0
                    }
                    prefInFlight = false
                    prefFlightOff = -1L
                    (lock as Object).notifyAll()
                }
            } catch (_: Throwable) {
                synchronized(lock) {
                    prefInFlight = false
                    prefFlightOff = -1L
                    pref = null
                    prefStart = -1L
                    prefLen = 0
                    (lock as Object).notifyAll()
                }
            }
        }
    }

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
            (lock as Object).notifyAll()
        }
        inner.close()
    }

    companion object {
        /** Large enough for solid member spans; still bounded for RAM (2 windows max). */
        const val SEQUENTIAL_WINDOW = 8 * 1024 * 1024
        const val RANDOM_WINDOW = 64 * 1024

        /**
         * Wait for an in-flight next-window prefetch before issuing a duplicate range.
         * 8 MiB @ ~50 Mbps ≈ 1.3 s — keep a generous cap.
         */
        private const val PREFETCH_WAIT_NS = 3_000_000_000L // 3 s

        private val PREFETCH_EXECUTOR = Executors.newCachedThreadPool { r ->
            Thread(r, "archive-readahead").apply { isDaemon = true }
        }
    }
}
