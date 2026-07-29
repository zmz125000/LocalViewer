package com.hippo.ehviewer.library

import androidx.annotation.Keep

/**
 * JNI-facing bridge for libarchive stream I/O.
 *
 * Keeps a file position; [nativeRead] / [nativeSeek] are called from native via
 * [GetMethodID] with fixed names — must not be renamed/shrunk by R8 (release).
 * Methods are synchronized: stream mode uses one shared position (native also holds
 * a mutex so only one extract runs at a time).
 */
@Keep
class ArchiveStreamBridge(
    private val source: ArchiveByteSource,
) {
    private var position: Long = 0L

    val size: Long get() = source.size

    /** Called from JNI: read up to [maxLen] bytes from current position. Empty = EOF. */
    @Keep
    @Suppress("unused") // JNI GetMethodID "nativeRead" "(I)[B"
    @Synchronized
    fun nativeRead(maxLen: Int): ByteArray {
        if (maxLen <= 0) return ByteArray(0)
        val fileSize = source.size
        val remaining = fileSize - position
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
     * @return new absolute position, or negative on failure ([ARCHIVE_FATAL] path).
     */
    @Keep
    @Suppress("unused") // JNI GetMethodID "nativeSeek" "(JI)J"
    @Synchronized
    fun nativeSeek(offset: Long, whence: Int): Long {
        val fileSize = source.size
        val next = when (whence) {
            0 -> offset
            1 -> position + offset
            2 -> fileSize + offset
            else -> return -1L
        }
        if (next < 0L || next > fileSize) return -1L
        position = next
        return position
    }

    @Synchronized
    fun close() {
        runCatching { source.close() }
    }
}
