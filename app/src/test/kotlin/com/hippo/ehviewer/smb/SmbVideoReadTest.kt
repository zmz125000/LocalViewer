package com.hippo.ehviewer.smb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbVideoReadTest {
    @Test
    fun `steady video refills before slow siblings and fills exact destination range`() = runBlocking {
        val chunk = SmbSequentialCopy.READ_CHUNK
        val refilled = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val buffer = ByteArray(8 * chunk + 20) { 99 }
        val read = SmbRangeReader { bytes, position, offset, length ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                val index = ((position - 123) / chunk).toInt()
                if (index == 4) refilled.countDown()
                if (index in 1..3) assertTrue("Window drained before refill", refilled.await(3, TimeUnit.SECONDS))
                bytes.fill(index.toByte(), offset, offset + length)
                length
            } finally {
                active.decrementAndGet()
            }
        }
        val length = 8 * chunk + 3
        assertEquals(length, readSmbVideoRange(read, 123, buffer, 7, length))
        assertTrue(peak.get() <= 4)
        for (i in 0 until length) assertEquals((i / chunk).toByte(), buffer[7 + i])
        assertTrue(buffer.take(7).all { it == 99.toByte() })
        assertTrue(buffer.drop(7 + length).all { it == 99.toByte() })
    }

    @Test
    fun `short replies are completed within each slot`() = runBlocking {
        val buffer = ByteArray(5 * SmbSequentialCopy.READ_CHUNK + 19)
        val read = SmbRangeReader { bytes, position, offset, length ->
            val count = minOf(length, 65536)
            for (i in 0 until count) bytes[offset + i] = ((position + i) % 251).toByte()
            count
        }
        assertEquals(buffer.size, readSmbVideoRange(read, 0, buffer, 0, buffer.size))
        for (i in buffer.indices) assertEquals((i % 251).toByte(), buffer[i])
    }

    @Test
    fun `early eof returns only the contiguous prefix`() = runBlocking {
        val chunk = SmbSequentialCopy.READ_CHUNK
        val end = chunk + 17L
        val buffer = ByteArray(8 * chunk)
        val read = SmbRangeReader { bytes, position, offset, length ->
            val count = minOf(length.toLong(), (end - position).coerceAtLeast(0)).toInt()
            bytes.fill(7, offset, offset + count)
            count
        }
        assertEquals(end.toInt(), readSmbVideoRange(read, 0, buffer, 0, buffer.size))
        assertTrue(buffer.take(end.toInt()).all { it == 7.toByte() })
    }

    @Test
    fun `seek cancellation stops old range without scheduling a refill`() = runBlocking {
        val aborted = AtomicBoolean()
        val calls = AtomicInteger()
        val buffer = ByteArray(8 * SmbSequentialCopy.READ_CHUNK)
        val read = SmbRangeReader { _, _, _, length ->
            calls.incrementAndGet()
            aborted.set(true)
            length
        }
        try {
            readSmbVideoRange(read, 0, buffer, 0, buffer.size, aborted::get)
            throw AssertionError("Cancelled video range completed")
        } catch (_: SmbReadCancelledException) {
            assertTrue(calls.get() <= 4)
        }
    }
}
