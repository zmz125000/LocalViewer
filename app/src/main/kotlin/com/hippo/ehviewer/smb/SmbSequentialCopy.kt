package com.hippo.ehviewer.smb

import com.hierynomus.smbj.share.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Sequential SMB copy: 1 MiB multi-credit READs with [READ_PIPELINE] outstanding
 * on one handle. 256 KiB stop-and-wait was ~150 Mbps per TCP on Win11 SMB3.
 */
internal fun interface SmbRangeReader {
    fun read(buf: ByteArray, fileOffset: Long, off: Int, len: Int): Int
}

internal object SmbSequentialCopy {
    const val READ_CHUNK = 1024 * 1024
    const val READ_PIPELINE = 4

    fun of(file: File): SmbRangeReader = SmbRangeReader { buf, fileOffset, off, len ->
        file.read(buf, fileOffset, off, len)
    }

    /**
     * Copy [maxBytes] from [start] (or until EOF if [maxBytes] is [Long.MAX_VALUE]).
     * [write] is invoked in file order on the caller thread.
     */
    fun copy(
        read: SmbRangeReader,
        start: Long,
        maxBytes: Long,
        isActive: () -> Boolean = { true },
        write: (buf: ByteArray, off: Int, len: Int) -> Unit,
    ): Long = runBlocking {
        copySuspending(read, start, maxBytes, isActive, write)
    }

    internal suspend fun copySuspending(
        read: SmbRangeReader,
        start: Long,
        maxBytes: Long,
        isActive: () -> Boolean,
        write: (buf: ByteArray, off: Int, len: Int) -> Unit,
    ): Long {
        require(start >= 0L)
        require(maxBytes >= 0L)
        if (maxBytes == 0L) return 0L
        val bufs = Array(READ_PIPELINE) { ByteArray(READ_CHUNK) }
        val sizes = IntArray(READ_PIPELINE)
        val got = IntArray(READ_PIPELINE)
        var offset = start
        var remaining = maxBytes
        var copied = 0L
        while (remaining > 0L && isActive() && coroutineContext.isActive) {
            coroutineContext.ensureActive()
            var batch = 0
            var batchBytes = 0L
            while (batch < READ_PIPELINE && batchBytes < remaining) {
                val n = minOf(READ_CHUNK.toLong(), remaining - batchBytes).toInt()
                if (n <= 0) break
                sizes[batch] = n
                batchBytes += n
                batch++
            }
            if (batch == 0) break
            val batchStart = offset
            coroutineScope {
                for (i in 0 until batch) {
                    val idx = i
                    val chunkOff = chunkOffset(sizes, idx)
                    launch(Dispatchers.IO) {
                        got[idx] = read.read(bufs[idx], batchStart + chunkOff, 0, sizes[idx])
                    }
                }
            }
            var short = false
            for (i in 0 until batch) {
                val n = got[i]
                if (n <= 0) {
                    short = true
                    break
                }
                write(bufs[i], 0, n)
                copied += n
                offset += n
                remaining -= n
                if (n < sizes[i]) {
                    short = true
                    break
                }
            }
            if (short) break
        }
        return copied
    }

    private fun chunkOffset(sizes: IntArray, index: Int): Long {
        var off = 0L
        for (i in 0 until index) off += sizes[i]
        return off
    }
}
