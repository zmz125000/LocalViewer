package com.hippo.ehviewer.smb

import com.hippo.ehviewer.library.copyTransferBlocksPipelined
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SmbTransferInputTest {
    @Test
    fun `relay uses a full concurrent read window and preserves bytes through pipelined writes`() = runBlocking {
        val data = ByteArray((SmbTransferCopy.READ_WINDOW + 2) * SmbTransferCopy.BLOCK_BYTES + 17) { (it % 251).toByte() }
        val output = ByteArray(data.size)
        val firstBatch = CountDownLatch(SmbTransferCopy.READ_WINDOW)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val read = SmbRangeReader { buffer, position, offset, length ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, current) }
            try {
                if (position < SmbTransferCopy.READ_WINDOW.toLong() * SmbTransferCopy.BLOCK_BYTES) {
                    firstBatch.countDown()
                    check(firstBatch.await(3, TimeUnit.SECONDS))
                }
                data.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
                length
            } finally {
                active.decrementAndGet()
            }
        }
        var acknowledged = 0L
        withTimeout(5000) {
            withSmbTransferInput(read, data.size.toLong(), {}) { input ->
                copyTransferBlocksPipelined(input, {}, { acknowledged += it }) { buffer, position, count ->
                    buffer.copyInto(output, position.toInt(), 0, count)
                    CompletableFuture.completedFuture(count.toLong())
                }
            }
        }
        assertArrayEquals(data, output)
        assertEquals(data.size.toLong(), acknowledged)
        assertEquals(SmbTransferCopy.READ_WINDOW, maximum.get())
        assertEquals(0, active.get())
    }

    @Test
    fun `short server read replies are completed without gaps or truncation`() = runBlocking {
        val data = ByteArray(2 * SmbSequentialCopy.READ_CHUNK + 17) { (it % 251).toByte() }
        val read = SmbRangeReader { buffer, position, offset, length ->
            val count = minOf(length, 64 * 1024)
            data.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            count
        }
        val output = withSmbTransferInput(read, data.size.toLong(), {}) { it.readBytes() }
        assertArrayEquals(data, output)
    }

    @Test
    fun `source failure reaches the destination stream`() = runBlocking {
        val read = SmbRangeReader { _, _, _, _ -> throw IOException("source disconnected") }
        try {
            withSmbTransferInput(read, 4L * SmbSequentialCopy.READ_CHUNK, {}) { it.readBytes() }
            fail("Expected source failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("source disconnected"))
        }
    }

    @Test
    fun `early EOF aborts other blocking reads before joining the window`() = runBlocking {
        val started = CountDownLatch(SmbTransferCopy.READ_WINDOW - 1)
        val released = CountDownLatch(1)
        val aborted = AtomicInteger()
        val active = AtomicInteger()
        val read = SmbRangeReader { _, position, _, _ ->
            if (position == 0L) {
                check(started.await(3, TimeUnit.SECONDS))
                -1
            } else {
                active.incrementAndGet()
                started.countDown()
                try {
                    check(released.await(3, TimeUnit.SECONDS)) { "Outstanding read was not aborted" }
                    throw IOException("handle closed")
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        try {
            withTimeout(5000) {
                withSmbTransferInput(read, SmbTransferCopy.READ_WINDOW.toLong() * SmbTransferCopy.BLOCK_BYTES, {
                    aborted.incrementAndGet()
                    released.countDown()
                }) { it.readBytes() }
            }
            fail("Expected truncated source failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("expected file size"))
            assertEquals(1, aborted.get())
            assertEquals(0, active.get())
        }
    }

    @Test
    fun `source ending early fails instead of silently uploading a partial file`() = runBlocking {
        val read = SmbRangeReader { _, _, _, _ -> -1 }
        try {
            withSmbTransferInput(read, 100, {}) { it.readBytes() }
            fail("Expected truncated source failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("expected file size"))
        }
    }

    @Test
    fun `destination failure unblocks outstanding reads before releasing the source handle`() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicInteger()
        val active = AtomicInteger()
        val read = SmbRangeReader { _, _, _, length ->
            active.incrementAndGet()
            started.countDown()
            try {
                check(released.await(3, TimeUnit.SECONDS)) { "Source read was not aborted" }
                length
            } finally {
                active.decrementAndGet()
            }
        }
        try {
            withTimeout(5000) {
                withSmbTransferInput(read, 4L * SmbSequentialCopy.READ_CHUNK, {
                    aborted.incrementAndGet()
                    released.countDown()
                }) {
                    check(started.await(3, TimeUnit.SECONDS))
                    throw IOException("destination failed")
                }
            }
            fail("Expected destination failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("destination failed"))
            assertEquals(1, aborted.get())
            assertEquals(0, active.get())
        }
    }

    @Test
    fun `cancellation unblocks source reads and joins the producer`() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicInteger()
        val read = SmbRangeReader { _, _, _, length ->
            started.countDown()
            check(released.await(3, TimeUnit.SECONDS)) { "Source read was not aborted" }
            length
        }
        val worker = launch(Dispatchers.IO) {
            withSmbTransferInput(read, 4L * SmbSequentialCopy.READ_CHUNK, {
                aborted.incrementAndGet()
                released.countDown()
            }) { awaitCancellation() }
        }
        check(started.await(3, TimeUnit.SECONDS))
        withTimeout(5000) { worker.cancelAndJoin() }
        assertEquals(1, aborted.get())
    }

    @Test
    fun `early consumer exit cannot strand a producer on a full pipe`() = runBlocking {
        val read = SmbRangeReader { buffer, _, offset, length ->
            buffer.fill(1, offset, offset + length)
            length
        }
        withTimeout(5000) {
            withSmbTransferInput(read, 100L * SmbSequentialCopy.READ_CHUNK, {}) {
                assertTrue(it.read() >= 0)
            }
        }
    }

    @Test
    fun `server-copy fallback starts at the first byte not already copied`() = runBlocking {
        val data = ByteArray(2 * SmbSequentialCopy.READ_CHUNK + 17) { (it % 251).toByte() }
        val start = SmbSequentialCopy.READ_CHUNK + 7L
        val read = SmbRangeReader { buffer, position, offset, length ->
            assertTrue(position >= start)
            data.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
            length
        }
        val output = withSmbTransferInput(read, data.size - start, {}, startOffset = start) { it.readBytes() }
        assertArrayEquals(data.copyOfRange(start.toInt(), data.size), output)
    }

    @Test
    fun `slow destination bounds read-ahead to the read window and one pipe block`() = runBlocking {
        val batch = CountDownLatch(SmbTransferCopy.READ_WINDOW)
        val calls = AtomicInteger()
        val read = SmbRangeReader { buffer, _, offset, length ->
            calls.incrementAndGet()
            batch.countDown()
            buffer.fill(1, offset, offset + length)
            length
        }
        withTimeout(5000) {
            withSmbTransferInput(read, 100L * SmbSequentialCopy.READ_CHUNK, {}) {
                check(batch.await(3, TimeUnit.SECONDS))
                kotlinx.coroutines.delay(50)
                assertTrue(calls.get() <= SmbTransferCopy.READ_WINDOW + 1)
            }
        }
    }

    @Test
    fun `empty source sends EOF without reading SMB`() = runBlocking {
        val calls = AtomicInteger()
        withSmbTransferInput(
            SmbRangeReader { _, _, _, _ ->
                calls.incrementAndGet()
                0
            },
            0,
            {},
        ) {
            assertEquals(-1, it.read())
        }
        assertEquals(0, calls.get())
    }
}
