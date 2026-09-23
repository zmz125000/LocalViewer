package com.hippo.ehviewer.gallery

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedSlotsTest {
    @Test
    fun `head uses the reserved slot and the next page uses fallback`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(2)
        val hold = CompletableDeferred<Unit>()
        val bothEntered = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        fun markEntered() {
            if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
        }
        val head = async {
            withOrderedPermits(
                rank = { 0 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 2,
            ) {
                markEntered()
                hold.await()
            }
        }
        val next = async {
            withOrderedPermits(
                rank = { 1 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 2,
            ) {
                markEntered()
                hold.await()
            }
        }
        bothEntered.await()
        assertEquals(0, serial.availablePermits)
        assertEquals(1, fallback.availablePermits)
        hold.complete(Unit)
        head.await()
        next.await()
    }

    @Test
    fun `page past the window does not take a slot`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(1)
        val started = AtomicInteger(0)
        val waiting = async {
            withOrderedPermits(
                rank = { 2 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 1,
            ) {
                started.incrementAndGet()
            }
        }
        delay(40)
        assertEquals(0, started.get())
        assertTrue(waiting.isActive)
        assertEquals(1, serial.availablePermits)
        assertEquals(1, fallback.availablePermits)
        waiting.cancel()
    }

    @Test
    fun `rank walks from the viewport and skips ready pages`() {
        val order = listOf(10, 11, 12, 13)
        assertEquals(0, orderedWorkRank(order, 10) { false })
        assertEquals(0, orderedWorkRank(order, 12) { it == 10 || it == 11 })
        assertEquals(1, orderedWorkRank(order, 13) { it == 10 || it == 11 })
        assertEquals(NOT_IN_ORDER, orderedWorkRank(order, 11) { it == 10 || it == 11 })
        assertEquals(NOT_IN_ORDER, orderedWorkRank(order, 99) { false })
        assertEquals(NOT_IN_ORDER, orderedWorkRank(emptyList(), 0) { false })
    }

    @Test
    fun `page left ready does not take a slot until it needs another copy`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(1)
        val stillReady = AtomicBoolean(true)
        val started = CompletableDeferred<Unit>()
        val job = async {
            withOrderedPermits(
                rank = { if (stillReady.get()) NOT_IN_ORDER else 0 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 1,
            ) {
                started.complete(Unit)
            }
        }
        delay(40)
        assertFalse(started.isCompleted)
        assertEquals(1, serial.availablePermits)
        stillReady.set(false)
        withTimeout(1_000) { started.await() }
        job.await()
    }

    @Test
    fun `second head waits for the reserved slot`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(2)
        val holdHead = CompletableDeferred<Unit>()
        val headEntered = CompletableDeferred<Unit>()
        val nextEntered = AtomicInteger(0)
        val head = async {
            withOrderedPermits(
                rank = { 0 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 2,
            ) {
                headEntered.complete(Unit)
                holdHead.await()
            }
        }
        headEntered.await()
        val next = async {
            withOrderedPermits(
                rank = { 0 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 2,
            ) {
                nextEntered.incrementAndGet()
            }
        }
        yield()
        yield()
        assertEquals(0, nextEntered.get())
        assertTrue(next.isActive)
        assertEquals(2, fallback.availablePermits)
        holdHead.complete(Unit)
        head.await()
        next.await()
        assertEquals(1, nextEntered.get())
    }

    @Test
    fun `page enters the window when an earlier page finishes`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(1)
        val headHold = CompletableDeferred<Unit>()
        val headDone = AtomicBoolean(false)
        val nextStarted = CompletableDeferred<Unit>()
        val head = async {
            withOrderedPermits(
                rank = { 0 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 1,
            ) {
                headHold.await()
                headDone.set(true)
            }
        }
        val later = async {
            withOrderedPermits(
                rank = { if (headDone.get()) 1 else 2 },
                serialSlots = serial,
                fallbackSlots = fallback,
                fallbackPermits = 1,
            ) {
                nextStarted.complete(Unit)
            }
        }
        delay(40)
        assertFalse(nextStarted.isCompleted)
        headHold.complete(Unit)
        head.await()
        withTimeout(1_000) { nextStarted.await() }
        later.await()
        assertTrue(nextStarted.isCompleted)
    }
}
