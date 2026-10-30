package com.hippo.ehviewer.smb

import java.util.ArrayDeque
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Four rolling READs into the caller's bounded range buffer; no extra payload buffers. */
internal suspend fun readSmbVideoRange(
    read: SmbRangeReader,
    fileOffset: Long,
    buffer: ByteArray,
    offset: Int,
    length: Int,
    shouldAbort: () -> Boolean = { false },
): Int = coroutineScope {
    require(fileOffset >= 0 && offset >= 0 && length >= 0 && length <= buffer.size - offset)
    val pending = ArrayDeque<Deferred<Int>>()
    var scheduled = 0
    var filled = 0

    fun checkActiveRange() {
        if (shouldAbort()) throw SmbReadCancelledException()
    }

    fun submit() {
        checkActiveRange()
        val at = scheduled
        val size = minOf(SmbSequentialCopy.READ_CHUNK, length - at)
        scheduled += size
        pending.addLast(
            async(Dispatchers.IO) {
                var received = 0
                while (received < size) {
                    checkActiveRange()
                    val count = read.read(buffer, fileOffset + at + received, offset + at + received, size - received)
                    checkActiveRange()
                    if (count <= 0) return@async received
                    check(count <= size - received) { "SMB read exceeded the requested range" }
                    received += count
                }
                received
            },
        )
    }

    try {
        repeat(SmbSequentialCopy.READ_PIPELINE) { if (scheduled < length) submit() }
        while (pending.isNotEmpty()) {
            checkActiveRange()
            val expected = minOf(SmbSequentialCopy.READ_CHUNK, length - filled)
            val count = pending.removeFirst().await()
            filled += count
            if (count < expected) break
            // Refill this slot without waiting for the other three READs.
            if (scheduled < length) submit()
        }
        checkActiveRange()
        if (filled == 0 && length > 0) -1 else filled
    } finally {
        pending.forEach { it.cancel() }
    }
}
