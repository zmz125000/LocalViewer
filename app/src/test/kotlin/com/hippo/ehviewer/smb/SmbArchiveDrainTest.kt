package com.hippo.ehviewer.smb

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbArchiveDrainTest {
    @Test
    fun drainUntilIdleReleasesAfterQuietPeriod() = runBlocking {
        val ch = Channel<Int>(Channel.UNLIMITED)
        ch.send(1)
        ch.send(2)
        val seen = ArrayList<Int>()
        val start = System.nanoTime()
        drainUntilIdle(ch, idleMs = 80L) { seen.add(it) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        assertEquals(listOf(1, 2), seen)
        assertTrue("idle wait was ${elapsedMs}ms", elapsedMs >= 60L)
        assertTrue(ch.isEmpty)
    }

    @Test
    fun drainUntilIdleKeepsHandleDuringBursts() = runBlocking {
        val ch = Channel<Int>(Channel.UNLIMITED)
        val seen = ArrayList<Int>()
        val job = launch {
            drainUntilIdle(ch, idleMs = 200L) { seen.add(it) }
        }
        ch.send(1)
        delay(40)
        ch.send(2)
        delay(40)
        ch.send(3)
        delay(250)
        job.join()
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun drainUntilIdleStopsWhenChannelCloses() = runBlocking {
        val ch = Channel<Int>(Channel.UNLIMITED)
        ch.send(9)
        ch.close()
        val seen = ArrayList<Int>()
        drainUntilIdle(ch, idleMs = 5_000L) { seen.add(it) }
        assertEquals(listOf(9), seen)
    }
}
