package com.hippo.ehviewer.smb

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSequentialCopyTest {
    @Test
    fun pipelinesOneMegabyteChunks() = runBlocking {
        val file = ByteArray(3 * 1024 * 1024 + 512) { i -> (i * 31).toByte() }
        val sizes = mutableListOf<Int>()
        val outstanding = AtomicInteger(0)
        val maxOutstanding = AtomicInteger(0)
        val reader = SmbRangeReader { buf, fileOffset, off, len ->
            val now = outstanding.incrementAndGet()
            maxOutstanding.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                synchronized(sizes) { sizes.add(len) }
                val n = minOf(len.toLong(), file.size - fileOffset).toInt().coerceAtLeast(0)
                if (n > 0) System.arraycopy(file, fileOffset.toInt(), buf, off, n)
                n
            } finally {
                outstanding.decrementAndGet()
            }
        }
        val out = ArrayList<Byte>()
        val copied = SmbSequentialCopy.copySuspending(
            read = reader,
            start = 0L,
            maxBytes = file.size.toLong(),
            isActive = { true },
        ) { buf, off, len ->
            for (i in 0 until len) out.add(buf[off + i])
        }
        assertEquals(file.size.toLong(), copied)
        assertEquals(file.toList(), out)
        assertEquals(listOf(1024 * 1024, 1024 * 1024, 1024 * 1024, 512), sizes)
        assertTrue("pipeline depth was ${maxOutstanding.get()}", maxOutstanding.get() >= 2)
        assertTrue(maxOutstanding.get() <= SmbSequentialCopy.READ_PIPELINE)
    }

    @Test
    fun stopsWhenInactive() = runBlocking {
        val calls = AtomicInteger(0)
        val reader = SmbRangeReader { buf, _, off, len ->
            calls.incrementAndGet()
            buf.fill(1, off, off + len)
            len
        }
        var batches = 0
        val copied = SmbSequentialCopy.copySuspending(
            read = reader,
            start = 0L,
            maxBytes = 8L * 1024 * 1024,
            isActive = { batches < 1 },
        ) { _, _, _ -> batches++ }
        assertEquals(SmbSequentialCopy.READ_CHUNK.toLong() * SmbSequentialCopy.READ_PIPELINE, copied)
        assertEquals(SmbSequentialCopy.READ_PIPELINE, calls.get())
    }
}
