package com.hippo.ehviewer.gallery

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedSlotsTest {
    @Test
    fun `serial page uses reserved slot while later page uses fallback`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(3)
        val hold = CompletableDeferred<Unit>()
        val bothEntered = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        fun markEntered() {
            if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
        }
        val head = async {
            withSerialOrFallbackPermit(
                isSerial = { true },
                serialSlots = serial,
                fallbackSlots = fallback,
            ) {
                markEntered()
                hold.await()
            }
        }
        val later = async {
            withSerialOrFallbackPermit(
                isSerial = { false },
                serialSlots = serial,
                fallbackSlots = fallback,
            ) {
                markEntered()
                hold.await()
            }
        }
        bothEntered.await()
        assertEquals(0, serial.availablePermits)
        assertEquals(2, fallback.availablePermits)
        hold.complete(Unit)
        head.await()
        later.await()
    }

    @Test
    fun `serial slot stays free when only fallback work is running`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(2)
        val hold = CompletableDeferred<Unit>()
        val body = async {
            withSerialOrFallbackPermit(
                isSerial = { false },
                serialSlots = serial,
                fallbackSlots = fallback,
            ) {
                assertEquals(1, serial.availablePermits)
                assertEquals(1, fallback.availablePermits)
                hold.await()
            }
        }
        yield()
        yield()
        assertTrue(body.isActive)
        assertEquals(1, serial.availablePermits)
        hold.complete(Unit)
        body.await()
    }

    @Test
    fun `serial work walks from the viewport and skips ready pages`() {
        val order = listOf(10, 11, 12, 13)
        assertEquals(10, serialWorkHead(order) { false })
        assertEquals(12, serialWorkHead(order) { it == 10 || it == 11 })
        assertEquals(null, serialWorkHead(order) { true })
        assertEquals(null, serialWorkHead(emptyList()) { false })
    }

    @Test
    fun `next serial page waits for the reserved slot`() = runBlocking {
        val serial = Semaphore(1)
        val fallback = Semaphore(2)
        val holdHead = CompletableDeferred<Unit>()
        val headEntered = CompletableDeferred<Unit>()
        val nextEntered = AtomicInteger(0)
        val head = async {
            withSerialOrFallbackPermit(
                isSerial = { true },
                serialSlots = serial,
                fallbackSlots = fallback,
            ) {
                headEntered.complete(Unit)
                holdHead.await()
            }
        }
        headEntered.await()
        val next = async {
            withSerialOrFallbackPermit(
                isSerial = { true },
                serialSlots = serial,
                fallbackSlots = fallback,
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
}
