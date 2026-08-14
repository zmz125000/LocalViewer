package com.hippo.ehviewer.library

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class VideoBackendHolderTest {
    @Test
    fun sameTokenReusesSource() {
        val env = Env()
        val first = env.holder.acquire("t1", "play1", env::open)
        val second = env.holder.acquire("t1", "play1", env::open)
        assertSame(first, second)
        assertEquals(1, env.holder.openCount.get())
        assertEquals(0, env.holder.closeCount.get())
        assertEquals(listOf("play1"), env.plays)
    }

    @Test
    fun newTokenEvictsPreviousImmediately() {
        val env = Env()
        val first = env.holder.acquire("t1", "play1", env::open)
        val second = env.holder.acquire("t2", "play2", env::open)
        assertNotSame(first, second)
        assertEquals(2, env.holder.openCount.get())
        assertEquals(1, env.holder.closeCount.get())
        assertEquals(listOf("play1", "play2"), env.plays)
    }

    @Test
    fun idleTimeoutClosesAndNextAcquireReopens() {
        val env = Env(idleMs = 60_000L)
        env.holder.acquire("t1", "play1", env::open)
        env.holder.touch()
        env.clock.set(59_999L)
        env.holder.checkIdle()
        assertEquals(0, env.holder.closeCount.get())
        env.clock.set(60_000L)
        env.holder.checkIdle()
        assertEquals(1, env.holder.closeCount.get())
        env.holder.acquire("t1", "play1-again", env::open)
        assertEquals(2, env.holder.openCount.get())
    }

    @Test
    fun manySeeksDoNotReopen() {
        val env = Env()
        val src = env.holder.acquire("t1", "play1", env::open)
        repeat(20) {
            env.holder.touch()
            assertSame(src, env.holder.acquire("t1", "seek", env::open))
        }
        assertEquals(1, env.holder.openCount.get())
        assertEquals(0, env.holder.closeCount.get())
        assertEquals(1, env.plays.size)
    }

    private class Env(idleMs: Long = 60_000L) {
        val clock = AtomicLong(0L)
        val plays = mutableListOf<String>()
        val holder: VideoBackendHolder = VideoBackendHolder(
            nowMs = { clock.get() },
            idleMs = idleMs,
            beginPlay = { reason ->
                plays.add(reason)
                holder.evict("video-play")
            },
        )

        fun open(): ArchiveByteSource = FakeSource()
    }

    private class FakeSource : ArchiveByteSource {
        private val closed = AtomicInteger(0)
        override val size: Long = 1_000_000L
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            val n = minOf(len, 64)
            return n
        }
        override fun close() {
            closed.incrementAndGet()
        }
    }
}
