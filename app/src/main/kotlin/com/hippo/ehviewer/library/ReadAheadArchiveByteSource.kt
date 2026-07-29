package com.hippo.ehviewer.library

/**
 * Windowed readahead over an [ArchiveByteSource].
 *
 * libarchive issues a mix of:
 * - **Random** seeks (EOCD tail, central directory, each local header)
 * - **Sequential** runs (compressed page payload, CD body)
 *
 * Blindly readahead 2 MiB on every miss is disastrous for ZIP listing: each local
 * header is ~100 bytes but we would pull 2 MiB of the following compressed member
 * (× N images ≈ full archive download). So:
 * - sequential hit (offset == end of window) → large window
 * - random seek → small window (header-sized region only)
 */
class ReadAheadArchiveByteSource(
    private val inner: ArchiveByteSource,
    private val sequentialWindow: Int = SEQUENTIAL_WINDOW,
    private val randomWindow: Int = RANDOM_WINDOW,
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
            if (win != null && offset >= winStart && offset + want <= winStart + winLen) {
                System.arraycopy(win!!, (offset - winStart).toInt(), buf, off, want)
                return want
            }
            val sequential = win != null && offset == winStart + winLen
            val window = if (sequential) sequentialWindow else randomWindow
            val fetch = minOf(window.toLong(), size - offset).toInt().coerceAtLeast(want)
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
        /** Sequential compressed payload / CD body (align with SMB folder throughput). */
        const val SEQUENTIAL_WINDOW = 2 * 1024 * 1024

        /**
         * After a seek (EOCD, local headers). Large enough for a header + extras,
         * small enough that N headers do not download the zip.
         */
        const val RANDOM_WINDOW = 64 * 1024
    }
}
