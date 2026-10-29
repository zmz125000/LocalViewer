package com.hippo.ehviewer.smb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTransferCopyTest {
    @Test
    fun `completed read slot refills before the rest of its batch completes`() = runBlocking {
        val refilled = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val read = SmbRangeReader { buffer, position, offset, length ->
            val index = (position / SmbTransferCopy.BLOCK_BYTES).toInt()
            if (index == SmbTransferCopy.READ_WINDOW) refilled.countDown()
            if (index in 1 until SmbTransferCopy.READ_WINDOW) {
                assertTrue("Read window was drained before refilling", refilled.await(3, TimeUnit.SECONDS))
            }
            buffer.fill(index.toByte(), offset, offset + length)
            length
        }
        SmbTransferCopy.copy(read, 0, (SmbTransferCopy.READ_WINDOW + 2L) * SmbTransferCopy.BLOCK_BYTES) { buffer, offset, length ->
            assertEquals(SmbTransferCopy.BLOCK_BYTES, length)
            val index = written.size
            assertTrue(buffer.sliceArray(offset until offset + length).all { it == index.toByte() })
            written += index
        }
        assertEquals((0 until SmbTransferCopy.READ_WINDOW + 2).toList(), written)
    }
}
