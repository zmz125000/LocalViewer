package com.hippo.ehviewer.library

/**
 * Windowed readahead for archive stream I/O (SMB keep-open or WebDAV Range).
 *
 * - **Sequential** (offset continues prior window) → large fetch (payload / CD body)
 * - **Random** (seek / miss) → small fetch (EOCD tail, local headers)
 *
 * Blind large readahead on every miss re-downloads zip members during header walks;
 * ZIP open now uses CD-only indexing, but extract still benefits from this split.
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
        const val SEQUENTIAL_WINDOW = 2 * 1024 * 1024
        const val RANDOM_WINDOW = 64 * 1024
    }
}
