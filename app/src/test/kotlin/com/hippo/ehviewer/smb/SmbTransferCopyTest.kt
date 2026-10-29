package com.hippo.ehviewer.smb

import com.hippo.ehviewer.gallery.KeyedJobRegistry
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTransferCopyTest {
    @Test
    fun `fast scroll cancels stale rolling reads without cancelling the retained page`() = runBlocking {
        val registry = KeyedJobRegistry<Int>()
        val window = SmbSequentialCopy.READ_PIPELINE
        val started = CountDownLatch(window)
        val closed = CountDownLatch(1)
        val active = AtomicInteger()
        val aborted = AtomicBoolean()
        val writes = AtomicInteger()
        val retained = Job()
        assertTrue(registry.register(10, retained))
        val stale = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                SmbTransferCopy.copy(
                    read = SmbRangeReader { _, _, _, _ ->
                        active.incrementAndGet()
                        started.countDown()
                        try {
                            check(closed.await(3, TimeUnit.SECONDS)) { "Stale read handle was not closed" }
                            throw IOException("handle closed")
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    start = 0,
                    size = 100L * SmbTransferCopy.BLOCK_BYTES,
                    readWindow = window,
                    onFailure = {
                        if (aborted.compareAndSet(false, true)) SmbAsyncClose.run { closed.countDown() }
                    },
                ) { _, _, _ -> writes.incrementAndGet() }
            } finally {
                registry.release(0, coroutineContext[Job])
            }
        }
        assertTrue(registry.register(0, stale))
        stale.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            registry.cancelOutside(setOf(10))
            withTimeout(3000) { stale.join() }
            assertTrue(stale.isCancelled)
            assertTrue(aborted.get())
            assertEquals(0, active.get())
            assertEquals(0, writes.get())
            assertEquals(null, registry.owner(0))
            assertTrue(retained.isActive)
        } finally {
            closed.countDown()
            registry.cancelAll()
        }
    }

    @Test
    fun `completed read slot refills before the rest of its batch completes`() = runBlocking {
        for (window in listOf(SmbSequentialCopy.READ_PIPELINE, SmbTransferCopy.READ_WINDOW)) {
            val refilled = CountDownLatch(1)
            val written = mutableListOf<Int>()
            val read = SmbRangeReader { buffer, position, offset, length ->
                val index = (position / SmbTransferCopy.BLOCK_BYTES).toInt()
                if (index == window) refilled.countDown()
                if (index in 1 until window) {
                    assertTrue("Read window was drained before refilling", refilled.await(3, TimeUnit.SECONDS))
                }
                buffer.fill(index.toByte(), offset, offset + length)
                length
            }
            SmbTransferCopy.copy(read, 0, (window + 2L) * SmbTransferCopy.BLOCK_BYTES, readWindow = window) { buffer, offset, length ->
                assertEquals(SmbTransferCopy.BLOCK_BYTES, length)
                val index = written.size
                assertTrue(buffer.sliceArray(offset until offset + length).all { it == index.toByte() })
                written += index
            }
            assertEquals((0 until window + 2).toList(), written)
        }
    }

    @Test
    fun `large image fills short replies with four reusable buffers and an exact tail`() = runBlocking {
        val size = 100L * SmbTransferCopy.BLOCK_BYTES + 13
        val buffers = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
        var written = 0L
        val read = SmbRangeReader { buffer, position, offset, length ->
            val count = minOf(length, 64 * 1024)
            assertTrue(position + count <= size)
            buffer.fill((position / SmbTransferCopy.BLOCK_BYTES).toByte(), offset, offset + count)
            count
        }
        SmbTransferCopy.copy(read, 0, size, readWindow = SmbSequentialCopy.READ_PIPELINE) { buffer, offset, length ->
            buffers += buffer
            val value = (written / SmbTransferCopy.BLOCK_BYTES).toByte()
            assertTrue((offset until offset + length).all { buffer[it] == value })
            written += length
        }
        assertEquals(size, written)
        assertEquals(SmbSequentialCopy.READ_PIPELINE, buffers.size)
    }
}
