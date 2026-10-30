package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbReadCancelledException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Replay inflated video bytes without restarting a sequential ZIP inflater on seeks. */
internal class SequentialVideoByteSource(
    private val inner: ArchiveByteSource,
    private val temporaryFile: File,
) : ArchiveByteSource {
    override val size: Long = inner.size
    private val cache = RandomAccessFile(temporaryFile, "rw")
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val scratch = ByteArray(256 * 1024)
    private var cachedTo = 0L

    init {
        // Android keeps the open inode alive. Process death also releases the temporary data.
        temporaryFile.delete()
    }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
        if (closed.get()) return -1
        if (len <= 0) return 0
        require(offset >= 0 && off >= 0 && len <= buf.size - off)
        if (offset >= size) return 0
        val want = minOf(len.toLong(), size - offset).toInt()
        val end = offset + want
        while (cachedTo < end) {
            if (closed.get()) return -1
            if (Thread.currentThread().isInterrupted) throw SmbReadCancelledException()
            val count = try {
                inner.readAt(cachedTo, scratch, 0, minOf(scratch.size.toLong(), end - cachedTo).toInt())
            } catch (error: Throwable) {
                if (Thread.currentThread().isInterrupted) throw SmbReadCancelledException()
                throw error
            }
            if (count <= 0) throw IOException("ZIP video ended before its expected size")
            cache.seek(cachedTo)
            cache.write(scratch, 0, count)
            cachedTo += count
        }
        cache.seek(offset)
        cache.readFully(buf, off, want)
        want
    }

    override fun dropQueuedReads() = inner.dropQueuedReads()

    override fun requestReconnect() = inner.requestReconnect()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Cancel remote I/O before waiting for a thread filling the prefix.
            inner.close()
        } finally {
            synchronized(lock) {
                try {
                    cache.close()
                } finally {
                    temporaryFile.delete()
                }
            }
        }
    }
}
