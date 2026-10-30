package com.hippo.ehviewer.library

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialVideoByteSourceTest {
    @Test
    fun prefixIsFilledOnDemandAndReplayedAfterBackwardSeek() {
        val calls = AtomicInteger()
        var nextOffset = 0L
        val lane = object : ArchiveByteSource {
            override val size = 4L * 1024 * 1024
            override val isRandomAccess = false
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                assertEquals("Sequential source must never be rewound", nextOffset, offset)
                calls.incrementAndGet()
                buf.fill(42, off, off + len)
                nextOffset += len
                return len
            }
            override fun close() = Unit
        }
        val temporary = File.createTempFile("video-prefix-", ".tmp")
        SequentialVideoByteSource(lane, temporary).use { source ->
            assertTrue(source.isRandomAccess)
            val bytes = ByteArray(32)
            assertEquals(32, source.readAt(0, bytes, 0, bytes.size))
            assertEquals(32L, nextOffset)
            assertEquals(32, source.readAt(lane.size - 32, bytes, 0, bytes.size))
            val fetched = calls.get()
            assertEquals(32, source.readAt(0, bytes, 0, bytes.size))
            assertEquals(fetched, calls.get())
            assertTrue(bytes.all { it == 42.toByte() })
        }
        assertFalse(temporary.exists())
    }

    @Test
    fun closingCancelsBlockedSourceBeforeWaitingForCacheLock() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closes = AtomicInteger()
        val lane = object : ArchiveByteSource {
            override val size = 1024L
            override val isRandomAccess = false
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                started.countDown()
                check(cancelled.await(3, TimeUnit.SECONDS)) { "Source was not cancelled before waiting on cache lock" }
                return -1
            }
            override fun close() {
                closes.incrementAndGet()
                cancelled.countDown()
            }
        }
        val temporary = File.createTempFile("video-close-", ".tmp")
        val source = SequentialVideoByteSource(lane, temporary)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                source.readAt(0, ByteArray(32), 0, 32)
            } catch (_: IOException) {
                // The source was closed during the read.
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        reader.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            source.close()
            source.close()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(1, closes.get())
            assertFalse(temporary.exists())
        } finally {
            cancelled.countDown()
            source.close()
            reader.join(3000)
        }
    }
}
