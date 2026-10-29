package com.hippo.ehviewer.smb

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Fixed RAM ring; independent of which IO dispatcher thread performs the next read/write. */
internal class SmbTransferPipe(capacity: Int) : InputStream() {
    private val buffer = ByteArray(capacity.also { require(it > 0) })
    private val lock = ReentrantLock()
    private val readable = lock.newCondition()
    private val writable = lock.newCondition()
    private var readAt = 0
    private var writeAt = 0
    private var count = 0
    private var finished = false
    private var closed = false
    private var failure: Throwable? = null

    fun write(source: ByteArray, offset: Int, length: Int) = lock.withLock {
        if (offset < 0 || length < 0 || offset > source.size - length) throw IndexOutOfBoundsException()
        var consumed = 0
        while (consumed < length) {
            while (count == buffer.size && !closed) writable.await()
            if (closed) throw IOException("Transfer stream closed")
            val size = minOf(length - consumed, buffer.size - count, buffer.size - writeAt)
            source.copyInto(buffer, writeAt, offset + consumed, offset + consumed + size)
            writeAt = (writeAt + size) % buffer.size
            count += size
            consumed += size
            readable.signalAll()
        }
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (offset < 0 || length < 0 || offset > destination.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return@withLock 0
        while (count == 0 && !finished && !closed) readable.await()
        if (closed) throw IOException("Transfer stream closed")
        if (count == 0) {
            failure?.let { throw it }
            return@withLock -1
        }
        val size = minOf(length, count, buffer.size - readAt)
        buffer.copyInto(destination, offset, readAt, readAt + size)
        readAt = (readAt + size) % buffer.size
        count -= size
        writable.signalAll()
        size
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    fun finish(error: Throwable?) = lock.withLock {
        failure = error
        finished = true
        readable.signalAll()
    }

    override fun close() = lock.withLock {
        closed = true
        readable.signalAll()
        writable.signalAll()
    }
}
