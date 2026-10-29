package com.hippo.ehviewer.smb

import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** Bounded rolling reads. Bulk transfers use eight slots; pooled image reads can use fewer. */
internal object SmbTransferCopy {
    const val BLOCK_BYTES = 1024 * 1024
    const val READ_WINDOW = 8

    suspend fun copy(
        read: SmbRangeReader,
        start: Long,
        size: Long,
        onFailure: (Throwable) -> Unit = {},
        readWindow: Int = READ_WINDOW,
        write: (ByteArray, Int, Int) -> Unit,
    ): Unit = try {
        coroutineScope {
            require(start >= 0 && size >= 0 && start <= Long.MAX_VALUE - size)
            require(readWindow in 1..READ_WINDOW)
            class Pending(val buffer: ByteArray, val length: Int, val result: Deferred<Unit>)
            val pending = ArrayDeque<Pending>()
            var scheduled = 0L

            fun submit(buffer: ByteArray) {
                val length = minOf(buffer.size.toLong(), size - scheduled).toInt()
                val position = start + scheduled
                scheduled += length
                val result = async(Dispatchers.IO) {
                    try {
                        var filled = 0
                        while (filled < length) {
                            coroutineContext.ensureActive()
                            val count = read.read(buffer, position + filled, filled, length - filled)
                            if (count <= 0) throw IOException("SMB source ended before the expected file size")
                            check(count <= length - filled) { "SMB read exceeded the requested range" }
                            filled += count
                        }
                    } catch (error: Throwable) {
                        onFailure(error)
                        throw error
                    }
                }
                pending.addLast(Pending(buffer, length, result))
            }

            try {
                repeat(readWindow) {
                    if (scheduled < size) submit(ByteArray(minOf(BLOCK_BYTES.toLong(), size - scheduled).toInt()))
                }
                while (pending.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val head = pending.removeFirst()
                    head.result.await()
                    write(head.buffer, 0, head.length)
                    // Refill each completed slot immediately instead of draining an entire batch.
                    if (scheduled < size) submit(head.buffer)
                }
            } catch (error: Throwable) {
                // Abort blocking SMB reads before coroutineScope waits for its children.
                onFailure(error)
                throw error
            }
        }
    } catch (error: Throwable) {
        // Closing a cancelled file can surface as IOException from a blocking READ.
        // Preserve caller cancellation instead of treating that expected close as failure.
        coroutineContext.ensureActive()
        throw error
    }
}
