package com.hippo.ehviewer.library

/**
 * Windowed readahead over an [ArchiveByteSource].
 *
 * libarchive stream I/O issues many sequential ~256–512 KiB reads (and seeks into
 * ZIP central / local headers). Without a window, each becomes a separate network
 * RTT. This serves hits from a [windowSize] RAM buffer and only refills on miss.
 */
class ReadAheadArchiveByteSource(
    private val inner: ArchiveByteSource,
    private val windowSize: Int = DEFAULT_WINDOW,
) : ArchiveByteSource {
    private val lock = Any()
    private var winStart: Long = -1L
    private var winLen: Int = 0
    private var win: ByteArray? = null

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (offset >= size) return 0
        val want = minOf(len.toLong(), size - offset).toInt()
        synchronized(lock) {
            // Fast path: fully inside window.
            if (win != null && offset >= winStart && offset + want <= winStart + winLen) {
                val from = (offset - winStart).toInt()
                System.arraycopy(win!!, from, buf, off, want)
                return want
            }
            // Refill window at [offset] (prefer large sequential runs after seeks).
            val fetch = minOf(windowSize.toLong(), size - offset).toInt().coerceAtLeast(want)
            val fresh = ByteArray(fetch)
            val got = inner.readAt(offset, fresh, 0, fetch)
            if (got <= 0) {
                winStart = -1L
                winLen = 0
                win = null
                return got
            }
            win = fresh
            winStart = offset
            winLen = got
            val n = minOf(want, got)
            System.arraycopy(fresh, 0, buf, off, n)
            return n
        }
    }

    override fun close() {
        synchronized(lock) {
            win = null
            winStart = -1L
            winLen = 0
        }
        inner.close()
    }

    companion object {
        /** 2 MiB — covers typical ZIP CD + several local headers / compressed pages. */
        const val DEFAULT_WINDOW = 2 * 1024 * 1024
    }
}
