package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingRangeMediaDataSourceTest {
    @Test
    fun readAt_fetchesSparsePagesWithoutFullSequentialPull() {
        val remote = ByteArray(1024 * 1024) { i -> (i % 251).toByte() }
        var reads = 0
        val source = object : ArchiveByteSource {
            override val size: Long = remote.size.toLong()
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                reads++
                if (offset < 0 || offset >= size) return -1
                val n = minOf(len.toLong(), size - offset).toInt()
                System.arraycopy(remote, offset.toInt(), buf, off, n)
                return n
            }
            override fun close() = Unit
        }
        val mds = CachingRangeMediaDataSource(source, pageSize = 64 * 1024, maxPages = 8)
        val buf = ByteArray(16)
        // Near start
        assertEquals(16, mds.readAt(100L, buf, 0, 16))
        assertEquals(remote[100], buf[0])
        // Near end (moov-style) — must not require filling the middle
        assertEquals(16, mds.readAt(remote.size - 32L, buf, 0, 16))
        assertEquals(remote[remote.size - 32], buf[0])
        // Sparse: only a few page fetches, not ~1MiB/64KiB of middle pages
        assertTrue("reads=$reads", reads in 1..8)
        mds.close()
    }

    @Test
    fun prefetch_warmsHeadAndTail() {
        val remote = ByteArray(512 * 1024) { it.toByte() }
        val source = object : ArchiveByteSource {
            override val size: Long = remote.size.toLong()
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (offset < 0 || offset >= size) return -1
                val n = minOf(len.toLong(), size - offset).toInt()
                System.arraycopy(remote, offset.toInt(), buf, off, n)
                return n
            }
            override fun close() = Unit
        }
        val mds = CachingRangeMediaDataSource(source, pageSize = 32 * 1024, maxPages = 16)
        mds.prefetch(0, 64 * 1024)
        mds.prefetch(remote.size - 32 * 1024L, 32 * 1024)
        val buf = ByteArray(8)
        assertEquals(8, mds.readAt(0, buf, 0, 8))
        assertEquals(8, mds.readAt(remote.size - 8L, buf, 0, 8))
        mds.close()
    }
}
