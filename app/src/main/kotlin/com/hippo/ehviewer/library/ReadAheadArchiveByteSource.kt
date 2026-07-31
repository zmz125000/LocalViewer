package com.hippo.ehviewer.library

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Windowed readahead for archive stream I/O (SMB keep-open or WebDAV Range).
 *
 * - **Sequential** (forward continuation / start-of-file) → large fetch
 * - **Random** (backward or far jump) → small fetch (EOCD tail, ZIP local headers)
 * - **Pipeline**: after a sequential fill, prefetch the **next** window on a worker so
 *   decompress / page-write can overlap network. Consumers that need any byte inside an
 *   in-flight next window **wait and reuse** it (not only when offset == window start),
 *   so sequential page extract + [warm] cannot double-download the same 8 MiB region.
 * - **[warm]** explicit next-page fill (up to [SEQUENTIAL_WINDOW]); no-op when already
 *   covered by current or pipeline window
 *
 * Sparse probes (`want` ≤ [RANDOM_WINDOW], non-solid) stay at the small window so
 * TAR 512‑byte header walks do not pull multi‑MiB member bodies.
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
    /** Expected end exclusive of in-flight fetch (flightOff + planned length). */
    private var prefFlightEnd: Long = -1L
    private var prefInFlight = false
    private var closed = false
    private val prefEpoch = AtomicInteger(0)

    override val size: Long
        get() = runCatching { inner.size }.getOrDefault(-1L)

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        return try {
            val fileSize = size
            if (fileSize <= 0L) return -1
            if (offset >= fileSize) return 0
            val want = minOf(len.toLong(), fileSize - offset).toInt()

            // Fast path: current window hit (no network).
            synchronized(lock) {
                if (closed) return -1
                if (copyFromWinLocked(offset, want, buf, off)) {
                    maybeKickPrefetchLocked(fileSize)
                    return want
                }
                // Promote completed prefetch if it fully covers this read.
                if (tryPromotePrefetchLocked(offset, want)) {
                    System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
                    maybeKickPrefetchLocked(fileSize)
                    return want
                }
            }

            // Wait for in-flight pipeline window that overlaps this read (any offset inside
            // the planned range — not only exact start). Prevents double Range for same 8 MiB.
            if (pipeline && awaitOverlappingPrefetch(offset, want, fileSize, buf, off)) {
                return want
            }
            if (closed) return -1

            // Re-check after wait race (another thread may have filled win).
            synchronized(lock) {
                if (closed) return -1
                if (copyFromWinLocked(offset, want, buf, off)) {
                    maybeKickPrefetchLocked(fileSize)
                    return want
                }
                if (tryPromotePrefetchLocked(offset, want)) {
                    System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
                    maybeKickPrefetchLocked(fileSize)
                    return want
                }
            }

            val window = chooseWindow(offset, want)
            val fetch = minOf(window.toLong(), fileSize - offset).toInt().coerceAtLeast(want)
            fillWindowSync(offset, fetch, want, buf, off, fileSize)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Prefill readahead at [offset] (e.g. next page local-header / data).
     * Fetches up to min([length], [sequentialWindow]) so multi‑MiB pages can land
     * in one network round-trip when the link is warm.
     *
     * No-op when current window, completed prefetch, or in-flight pipeline already
     * covers the requested range (avoids warm/extract refiring the same traffic).
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
            // Wait for pipeline if it already covers (or overlaps) this warm range.
            if (pipeline && awaitOverlappingPrefetch(offset, want, fileSize)) {
                return
            }
            if (closed) return
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

    /**
     * Block until an in-flight prefetch that **overlaps** [offset, offset+want) finishes,
     * then promote/serve if the completed window covers the full want.
     *
     * Returns true when the read was satisfied from the promoted window.
     * Returns false when there is nothing useful to wait for (caller should fetch).
     *
     * Waits until completion — no short timeout that re-issues the same range on slow
     * links. [close] notifies waiters so this cannot hang past source teardown.
     */
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
                if (copyFromWinLocked(offset, want, copyBuf, copyOff)) {
                    maybeKickPrefetchLocked(fileSize)
                    return true
                }
                if (tryPromotePrefetchLocked(offset, want)) {
                    if (copyBuf != null) {
                        System.arraycopy(win!!, (offset - winStart).toInt(), copyBuf, copyOff, want)
                        maybeKickPrefetchLocked(fileSize)
                    }
                    return true
                }
                // Wait only if in-flight range can fully cover this read once complete
                // (or already overlaps so we must not start a competing fetch).
                wait = prefInFlight && (
                    rangeFullyInsideFlightLocked(offset, want) ||
                        rangeOverlapsFlightLocked(offset, want)
                    )
                if (wait) {
                    try {
                        (lock as Object).wait(50L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            if (!wait) {
                // After flight ended without covering us — check completed pref once more.
                synchronized(lock) {
                    if (tryPromotePrefetchLocked(offset, want)) {
                        if (copyBuf != null) {
                            System.arraycopy(win!!, (offset - winStart).toInt(), copyBuf, copyOff, want)
                            maybeKickPrefetchLocked(fileSize)
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    /** Caller holds [lock]. */
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

    /** Caller holds [lock]. */
    private fun rangeFullyInWinLocked(offset: Long, want: Int): Boolean {
        if (win == null) return false
        return offset >= winStart && offset + want <= winStart + winLen
    }

    /** Caller holds [lock]. In-flight planned range fully covers [offset, offset+want). */
    private fun rangeFullyInsideFlightLocked(offset: Long, want: Int): Boolean {
        if (!prefInFlight || prefFlightOff < 0L || prefFlightEnd <= prefFlightOff) return false
        return offset >= prefFlightOff && offset + want <= prefFlightEnd
    }

    /** Caller holds [lock]. Any overlap with in-flight planned range. */
    private fun rangeOverlapsFlightLocked(offset: Long, want: Int): Boolean {
        if (!prefInFlight || prefFlightOff < 0L || prefFlightEnd <= prefFlightOff) return false
        val end = offset + want
        return offset < prefFlightEnd && end > prefFlightOff
    }

    /**
     * Pick fetch size.
     *
     * Solid ([preferSequential]) always uses the large window so decompress stays saturated.
     *
     * Sparse probes ([want] ≤ [randomWindow]) — TAR 512‑byte headers, ZIP local 30‑byte
     * headers — must **not** expand to 8 MiB. Otherwise TAR header walk treats each
     * multi‑MiB body gap as "forward sequential" and downloads nearly the whole archive
     * just to list members.
     */
    private fun chooseWindow(offset: Long, want: Int): Int {
        // Index / header probes: cap at random window (never pull image bodies).
        if (!preferSequential && want > 0 && want <= randomWindow) {
            return randomWindow
        }
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
        fileSize: Long = size,
    ): Int {
        // Last chance: do not network if another thread filled while we chose fetch size.
        val need = if (copyLen > 0) copyLen else fetch
        synchronized(lock) {
            if (closed) return -1
            if (copyLen > 0 && copyBuf != null && copyFromWinLocked(offset, copyLen, copyBuf, copyOff)) {
                maybeKickPrefetchLocked(fileSize)
                return copyLen
            }
            if (copyLen <= 0 && rangeFullyInWinLocked(offset, need.coerceAtLeast(1).coerceAtMost(sequentialWindow))) {
                return winLen
            }
            if (tryPromotePrefetchLocked(offset, need.coerceAtLeast(1))) {
                if (copyLen > 0 && copyBuf != null) {
                    System.arraycopy(win!!, (offset - winStart).toInt(), copyBuf, copyOff, copyLen)
                    maybeKickPrefetchLocked(fileSize)
                    return copyLen
                }
                return winLen
            }
        }

        val fresh = ByteArray(fetch)
        val got = try {
            inner.readAt(offset, fresh, 0, fetch)
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
            win = fresh
            winStart = offset
            winLen = got
            // Invalidate stale prefetch that does not continue this window.
            if (pref != null && prefStart != offset + got) {
                pref = null
                prefLen = 0
                prefStart = -1L
            }
            maybeKickPrefetchLocked(fileSize)
            if (copyBuf != null && copyLen > 0) {
                val n = minOf(copyLen, got)
                System.arraycopy(fresh, 0, copyBuf, copyOff, n)
                return n
            }
            return got
        }
    }

    /** Caller holds [lock]. Promote pref only when it fully covers [offset, offset+want). */
    private fun tryPromotePrefetchLocked(offset: Long, want: Int): Boolean {
        val p = pref ?: return false
        if (want <= 0) return false
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
    private fun maybeKickPrefetchLocked(fileSize: Long = size) {
        if (!pipeline || closed) return
        if (win == null || winLen <= 0) return
        if (fileSize <= 0L) return
        val nextOff = winStart + winLen
        if (nextOff >= fileSize) return
        if (prefInFlight) return
        if (pref != null && prefStart == nextOff) return
        // Only pipeline when we are consuming a full-size sequential window (not tiny random).
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
                    // Dropped before network: clear in-flight so waiters do not hang.
                    clearPrefetchFlight(nextOff)
                    return@execute
                }
                val buf = ByteArray(fetch)
                val got = inner.readAt(nextOff, buf, 0, fetch)
                synchronized(lock) {
                    if (closed || epoch != prefEpoch.get()) {
                        clearPrefetchFlightLocked(nextOff)
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
                    prefFlightEnd = -1L
                    (lock as Object).notifyAll()
                }
            } catch (_: Throwable) {
                synchronized(lock) {
                    prefInFlight = false
                    prefFlightOff = -1L
                    prefFlightEnd = -1L
                    pref = null
                    prefStart = -1L
                    prefLen = 0
                    (lock as Object).notifyAll()
                }
            }
        }
    }

    /** Clear in-flight flag for [flightOff] if we still own that flight (notifies waiters). */
    private fun clearPrefetchFlight(flightOff: Long) {
        synchronized(lock) {
            clearPrefetchFlightLocked(flightOff)
        }
    }

    /** Caller holds [lock]. */
    private fun clearPrefetchFlightLocked(flightOff: Long) {
        if (prefInFlight && prefFlightOff == flightOff) {
            prefInFlight = false
            prefFlightOff = -1L
            prefFlightEnd = -1L
        }
        (lock as Object).notifyAll()
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
            prefFlightEnd = -1L
            (lock as Object).notifyAll()
        }
        inner.close()
    }

    companion object {
        /** Large enough for solid member spans; still bounded for RAM (2 windows max). */
        const val SEQUENTIAL_WINDOW = 8 * 1024 * 1024
        const val RANDOM_WINDOW = 64 * 1024

        private val PREFETCH_EXECUTOR = Executors.newCachedThreadPool { r ->
            Thread(r, "archive-readahead").apply { isDaemon = true }
        }
    }
}
