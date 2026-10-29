package com.hippo.ehviewer.smb

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTransferCopyTest {
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
