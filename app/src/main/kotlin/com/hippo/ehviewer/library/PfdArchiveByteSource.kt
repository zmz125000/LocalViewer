package com.hippo.ehviewer.library

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.IOException

/**
 * Random-access [ArchiveByteSource] over a [ParcelFileDescriptor]
 * (real files and SAF `content://` tree documents).
 */
class PfdArchiveByteSource(
    private val pfd: ParcelFileDescriptor,
    private val ownsPfd: Boolean = true,
) : ArchiveByteSource {
    override val size: Long = pfd.statSize.coerceAtLeast(0L)

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (size <= 0L || offset >= size) return 0
        val want = minOf(len.toLong(), size - offset).toInt()
        return try {
            val n = Os.pread(pfd.fileDescriptor, buf, off, want, offset)
            if (n < 0) -1 else n
        } catch (e: Throwable) {
            throw IOException("pread failed at $offset", e)
        }
    }

    override fun close() {
        if (ownsPfd) runCatching { pfd.close() }
    }
}
