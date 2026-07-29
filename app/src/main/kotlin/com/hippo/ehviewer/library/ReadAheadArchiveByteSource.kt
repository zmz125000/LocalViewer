package com.hippo.ehviewer.library

/**
 * Windowed readahead for archive stream I/O (SMB keep-open or WebDAV Range).
 *
 * - **Sequential** (offset continues prior window) → large fetch (payload / CD body)
 * - **Random** (seek / miss) → small fetch (EOCD tail, local headers)
 * - **[warm]** explicit next-page fill (up to [SEQUENTIAL_WINDOW]) so the following
 *   extract hits RAM instead of paying a cold Range/SMB open RTT
 *
 * Blind large readahead on every miss re-downloads zip members during header walks;
 * ZIP open uses CD-only indexing; page extract + warm use the sequential path.
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
            return fillWindow(offset, fetch, want, buf, off)
        }
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
            if (win != null && offset >= winStart && offset + want <= winStart + winLen) {
                return
            }
            fillWindow(offset, want, 0, null, 0)
        }
    }

    /** Caller holds [lock]. [copyBuf] null → warm only (discard into window). */
    private fun fillWindow(
        offset: Long,
        fetch: Int,
        copyLen: Int,
        copyBuf: ByteArray?,
        copyOff: Int,
    ): Int {
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
        if (copyBuf != null && copyLen > 0) {
            val n = minOf(copyLen, got)
            System.arraycopy(fresh, 0, copyBuf, copyOff, n)
            return n
        }
        return got
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
        /** Large enough for most comic pages in one fetch; still bounded for RAM. */
        const val SEQUENTIAL_WINDOW = 8 * 1024 * 1024
        const val RANDOM_WINDOW = 64 * 1024
    }
}
