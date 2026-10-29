package com.hippo.ehviewer.library

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferPipelineTest {
    @Test
    fun `write window overlaps and pinned buffers preserve bytes through fragmented source reads`() = runBlocking {
        val data = ByteArray((TRANSFER_WRITE_WINDOW + 2) * TRANSFER_BLOCK_BYTES + 17) { (it % 251).toByte() }
        val output = ByteArray(data.size)
        val source = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 32 * 1024))
        }
        val buffers = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
        var submissions = 0
        var outstanding = 0
        var maximum = 0
        var acknowledged = 0L
        copyTransferBlocksPipelined(source, {}, { acknowledged += it }) { buffer, offset, count ->
            buffers.add(buffer)
            submissions++
            outstanding++
            maximum = maxOf(maximum, outstanding)
            object : CompletableFuture<Long>() {
                override fun get(timeout: Long, unit: TimeUnit): Long {
                    if (offset == 0L) assertEquals(TRANSFER_WRITE_WINDOW, submissions)
                    // Simulate a transport retaining the original buffer until acknowledgement.
                    buffer.copyInto(output, offset.toInt(), 0, count)
                    outstanding--
                    complete(count.toLong())
                    return super.get(timeout, unit)
                }
            }
        }
        assertArrayEquals(data, output)
        assertEquals(data.size.toLong(), acknowledged)
        assertEquals(TRANSFER_WRITE_WINDOW, maximum)
        assertEquals(TRANSFER_WRITE_WINDOW, buffers.size)
        assertEquals(0, outstanding)
    }

    @Test
    fun `range copy respects source length and destination offset for server-copy fallback`() = runBlocking {
        val input = ByteArrayInputStream(ByteArray(4 * TRANSFER_BLOCK_BYTES))
        val offsets = mutableListOf<Long>()
        val counts = mutableListOf<Int>()
        val length = 2L * TRANSFER_BLOCK_BYTES + 17
        copyTransferBlocksPipelined(input, {}, {}, startOffset = 7L * TRANSFER_BLOCK_BYTES, maxBytes = length) { _, offset, count ->
            offsets += offset
            counts += count
            CompletableFuture.completedFuture(count.toLong())
        }
        assertEquals(listOf(7L, 8L, 9L).map { it * TRANSFER_BLOCK_BYTES }, offsets)
        assertEquals(listOf(TRANSFER_BLOCK_BYTES, TRANSFER_BLOCK_BYTES, 17), counts)
        assertEquals(4L * TRANSFER_BLOCK_BYTES - length, input.available().toLong())
    }

    @Test
    fun `failed acknowledgement cancels the remaining window and reports no failed bytes`() = runBlocking {
        val failure = IOException("server write failed")
        val futures = mutableListOf<CompletableFuture<Long>>()
        var reported = 0L
        try {
            copyTransferBlocksPipelined(ByteArrayInputStream(ByteArray(2 * TRANSFER_WRITE_WINDOW * TRANSFER_BLOCK_BYTES)), {}, { reported += it }) { _, _, count ->
                CompletableFuture<Long>().also {
                    if (futures.isEmpty()) it.completeExceptionally(failure)
                    futures += it
                }
            }
            fail("Expected write failure")
        } catch (error: IOException) {
            assertSame(failure, error)
            assertEquals(0L, reported)
            assertEquals(TRANSFER_WRITE_WINDOW, futures.size)
            assertTrue(futures.drop(1).all { it.isCancelled })
        }
    }

    @Test
    fun `stop cancels all submitted writes before reading further input`() = runBlocking {
        val futures = mutableListOf<CompletableFuture<Long>>()
        var stop = false
        try {
            copyTransferBlocksPipelined(
                ByteArrayInputStream(ByteArray(8 * TRANSFER_BLOCK_BYTES)),
                { if (stop) throw CancellationException("stopped") },
                {},
            ) { _, _, _ ->
                CompletableFuture<Long>().also {
                    futures += it
                    if (futures.size == 2) stop = true
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, futures.size)
            assertTrue(futures.all { it.isCancelled })
        }
    }

    @Test
    fun `short acknowledgements fail instead of recycling an unconfirmed buffer`() = runBlocking {
        try {
            copyTransferBlocksPipelined(ByteArrayInputStream(ByteArray(17)), {}, {}) { _, _, count ->
                CompletableFuture.completedFuture(count.toLong() - 1)
            }
            fail("Expected incomplete write failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("acknowledged"))
        }
    }

    @Test
    fun `acknowledgements have a finite timeout and cancel the outstanding request`() = runBlocking {
        val future = object : CompletableFuture<Long>() {
            override fun get(timeout: Long, unit: TimeUnit): Long {
                assertEquals(120_000L, unit.toMillis(timeout))
                throw TimeoutException("simulated timeout")
            }
        }
        try {
            copyTransferBlocksPipelined(ByteArrayInputStream(ByteArray(17)), {}, {}) { _, _, _ -> future }
            fail("Expected timeout")
        } catch (error: IOException) {
            assertTrue(error.cause is TimeoutException)
            assertTrue(future.isCancelled)
        }
    }

    @Test
    fun `known-size truncated source fails and cancels pending data`() = runBlocking {
        val future = CompletableFuture<Long>()
        try {
            copyTransferBlocksPipelined(ByteArrayInputStream(ByteArray(17)), {}, {}, maxBytes = 100) { _, _, _ -> future }
            fail("Expected truncated source failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("expected file size"))
            assertTrue(future.isCancelled)
        }
    }

    @Test
    fun `empty source sends no writes`() = runBlocking {
        var submissions = 0
        copyTransferBlocksPipelined(ByteArrayInputStream(byteArrayOf()), {}, {}) { _, _, count ->
            submissions++
            CompletableFuture.completedFuture(count.toLong())
        }
        assertEquals(0, submissions)
    }
}
