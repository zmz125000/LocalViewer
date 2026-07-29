package com.hippo.ehviewer.library

/**
 * JNI-facing bridge for libarchive stream I/O.
 * Keeps a file position; [nativeRead] / [nativeSeek] are called from native threads.
 */
class ArchiveStreamBridge(
    private val source: ArchiveByteSource,
) {
    @Volatile
    private var position: Long = 0L

    val size: Long get() = source.size

    /** Called from JNI: read up to [maxLen] bytes from current position. Empty = EOF. */
    @Suppress("unused") // JNI
    fun nativeRead(maxLen: Int): ByteArray {
        if (maxLen <= 0) return ByteArray(0)
        val remaining = source.size - position
        if (remaining <= 0L) return ByteArray(0)
        val n = minOf(maxLen.toLong(), remaining).toInt()
        val buf = ByteArray(n)
        val got = source.readAt(position, buf, 0, n)
        if (got <= 0) return ByteArray(0)
        position += got
        return if (got == n) buf else buf.copyOf(got)
    }

    /**
     * Called from JNI. [whence]: 0=SEEK_SET, 1=SEEK_CUR, 2=SEEK_END.
     * @return new absolute position.
     */
    @Suppress("unused") // JNI
    fun nativeSeek(offset: Long, whence: Int): Long {
        val size = source.size
        val next = when (whence) {
            0 -> offset
            1 -> position + offset
            2 -> size + offset
            else -> position
        }
        position = next.coerceIn(0L, size)
        return position
    }

    fun close() {
        runCatching { source.close() }
    }
}
