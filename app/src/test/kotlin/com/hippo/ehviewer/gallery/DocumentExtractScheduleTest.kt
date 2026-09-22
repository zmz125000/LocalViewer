package com.hippo.ehviewer.gallery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentExtractScheduleTest {
    @Test
    fun `background work waits until the page tree is listed`() {
        assertTrue(deferDocumentBackgroundWork(structureComplete = false))
        assertFalse(deferDocumentBackgroundWork(structureComplete = true))
    }

    @Test
    fun `only the viewport may extract while indexing`() {
        val visible = 0..0
        assertTrue(documentExtractIsVisible(0, visible, orgImg = false))
        assertFalse(documentExtractIsVisible(1, visible, orgImg = false))
        assertFalse(documentExtractIsVisible(2, visible, orgImg = false))
        assertTrue(documentExtractIsVisible(3, visible, orgImg = true))
    }

    @Test
    fun `dual-page viewport is visible on both slots`() {
        assertTrue(documentExtractIsVisible(4, 4..5, orgImg = false))
        assertTrue(documentExtractIsVisible(5, 4..5, orgImg = false))
        assertFalse(documentExtractIsVisible(6, 4..5, orgImg = false))
    }

    @Test
    fun `open race treats an unknown viewport as visible`() {
        assertTrue(documentExtractIsVisible(0, null, orgImg = false))
    }

    @Test
    fun `freedom slot does not take the blocking parser lane`() {
        assertTrue(documentExtractWaitsForParser(interactive = true))
        assertFalse(documentExtractWaitsForParser(interactive = false))
    }

    @Test
    fun `freedom extract yields while the serial page is pending`() = runBlocking {
        val mutex = Mutex()
        val pending = ConcurrentHashMap.newKeySet<Int>()
        pending.add(0)
        val entered = AtomicInteger(0)
        val freedom = async {
            withDocumentParserAccess(
                waitForParser = false,
                retryWhileIdle = true,
                interactivePending = pending,
                extractMutex = mutex,
            ) {
                entered.incrementAndGet()
            }
        }
        delay(40)
        assertEquals(0, entered.get())
        assertTrue(freedom.isActive)
        assertTrue(mutex.tryLock())
        mutex.unlock()
        pending.clear()
        freedom.await()
        assertEquals(1, entered.get())
    }

    @Test
    fun `serial extract is not queued behind a waiting freedom slot`() = runBlocking {
        val mutex = Mutex()
        val pending = ConcurrentHashMap.newKeySet<Int>()
        pending.add(0)
        val freedomEntered = AtomicInteger(0)
        val serialEntered = AtomicInteger(0)
        val freedom = async {
            withDocumentParserAccess(
                waitForParser = false,
                retryWhileIdle = true,
                interactivePending = pending,
                extractMutex = mutex,
            ) {
                freedomEntered.incrementAndGet()
            }
        }
        delay(20)
        withDocumentParserAccess(
            waitForParser = true,
            retryWhileIdle = false,
            interactivePending = pending,
            extractMutex = mutex,
        ) {
            serialEntered.incrementAndGet()
        }
        assertEquals(1, serialEntered.get())
        assertEquals(0, freedomEntered.get())
        pending.clear()
        freedom.await()
        assertEquals(1, freedomEntered.get())
    }
}
