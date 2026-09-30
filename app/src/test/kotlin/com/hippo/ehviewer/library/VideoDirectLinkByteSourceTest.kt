package com.hippo.ehviewer.library

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDirectLinkByteSourceTest {
    @Test
    fun seekDropsQueuedReadsBeforeDemand() {
        val lane = RecordingSource(size = 64L * 1024 * 1024)
        val video = VideoDirectLinkByteSource(
            demand = lane,
            prefetch = null,
            knownSize = lane.size,
            blockSize = 1024,
            maxBlocks = 8,
            prefetchAhead = 4,
        )
        val buf = ByteArray(16)
        assertTrue(video.readAt(0L, buf, 0, 16) > 0)
        val afterOpen = lane.reads.toList()
        assertTrue(video.readAt(20L * 1024, buf, 0, 16) > 0)
        assertTrue("seek must drop queued prefetch", lane.drops.get() >= 1)
        val seekReads = lane.reads.drop(afterOpen.size)
        assertTrue(
            "first demand after jump must be the seek block, not leftover runway: $seekReads",
            seekReads.firstOrNull() == 20L * 1024 ||
                seekReads.any { it == 20L * 1024 },
        )
        video.close()
    }

    @Test
    fun sequentialSourceDoesNotPrefetchFarAheadInParallel() {
        val lane = RecordingSource(size = 32L * 1024, isRandomAccess = false)
        val video = VideoDirectLinkByteSource(
            demand = lane,
            prefetch = null,
            knownSize = lane.size,
            blockSize = 1024,
            maxBlocks = 8,
            prefetchAhead = 8,
            prefetchParallel = 4,
        )
        val buf = ByteArray(16)
        assertTrue(video.readAt(0L, buf, 0, 16) > 0)
        Thread.sleep(200)
        val starts = lane.reads.toSet()
        assertTrue("must read the demand block", 0L in starts)
        assertTrue(
            "deflate-style sources must not jump many blocks ahead: $starts",
            starts.all { it <= 2L * 1024 },
        )
        video.close()
    }

    @Test
    fun seekStartupReadsSequential256kAndKeepsShortHeaderProbe() {
        val lane = RecordingSource(size = 32L * 1024 * 1024)
        val video = VideoDirectLinkByteSource(
            demand = lane,
            prefetch = null,
            knownSize = lane.size,
            blockSize = 1024 * 1024,
            maxBlocks = 8,
            prefetchAhead = 8,
            prefetchParallel = 4,
        )
        video.noteSeek(System.currentTimeMillis() + 60_000)
        val buf = ByteArray(512 * 1024)
        val n = video.readAt(8L * 1024 * 1024, buf, 0, buf.size)
        assertEquals(VideoDirectLinkByteSource.SEEK_STARTUP_CHUNK, n)
        Thread.sleep(200)
        assertEquals("seek startup must not arm the 4-wide runway", 1, lane.reads.size)
        assertEquals(8L * 1024 * 1024, lane.reads[0])
        assertEquals(VideoDirectLinkByteSource.SEEK_STARTUP_CHUNK, lane.readLens[0])
        val header = ByteArray(32)
        assertEquals(32, video.readAt(0L, header, 0, header.size))
        assertEquals("header probe stays the requested length", 32, lane.readLens.last())
        assertEquals(0L, lane.reads.last())
        video.close()
    }

    @Test
    fun expiredSeekStartupUsesBlockReadsAgain() {
        val lane = RecordingSource(size = 8L * 1024 * 1024)
        val video = VideoDirectLinkByteSource(
            demand = lane,
            prefetch = null,
            knownSize = lane.size,
            blockSize = 1024,
            maxBlocks = 8,
            prefetchAhead = 4,
            prefetchParallel = 4,
        )
        video.noteSeek(System.currentTimeMillis() - 1)
        val buf = ByteArray(16)
        assertTrue(video.readAt(0L, buf, 0, buf.size) > 0)
        assertEquals(1024, lane.readLens.first())
        video.close()
    }

    private class RecordingSource(
        override val size: Long,
        override val isRandomAccess: Boolean = true,
    ) : ArchiveByteSource {
        val reads = CopyOnWriteArrayList<Long>()
        val readLens = CopyOnWriteArrayList<Int>()
        val drops = AtomicInteger(0)

        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            reads.add(offset)
            readLens.add(len)
            val n = minOf(len, (size - offset).toInt().coerceAtLeast(0))
            if (n <= 0) return 0
            buf.fill(1, off, off + n)
            return n
        }

        override fun dropQueuedReads() {
            drops.incrementAndGet()
        }

        override fun close() = Unit
    }
}
