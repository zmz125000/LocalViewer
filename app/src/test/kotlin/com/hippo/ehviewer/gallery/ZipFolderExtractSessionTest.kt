package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipFolderExtractSessionTest {
    @Test
    fun closeAbortsSourceWhileCdIsOpening() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val src = object : ArchiveByteSource {
            override val size: Long
                get() {
                    started.countDown()
                    check(closed.await(5, TimeUnit.SECONDS)) { "close() did not abort size()" }
                    return 100L
                }

            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int) = -1

            override fun close() {
                closeCount.incrementAndGet()
                closed.countDown()
            }
        }
        val session = ZipFolderExtractSession { src }
        val opener = thread { session.cd() }
        assertTrue("cd() never opened the source", started.await(2, TimeUnit.SECONDS))
        val startNs = System.nanoTime()
        session.close()
        opener.join(2_000)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("close waited ${elapsedMs}ms for ZIP open", elapsedMs < 500L)
        assertTrue("source.close() not called", closeCount.get() >= 1)
        assertTrue("cd() thread still blocked", !opener.isAlive)
    }
}
