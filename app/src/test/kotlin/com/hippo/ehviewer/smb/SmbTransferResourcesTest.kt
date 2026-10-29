package com.hippo.ehviewer.smb

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SmbTransferResourcesTest {
    private class Session(var connected: Boolean = true, var closes: Int = 0)

    private fun resources() = SmbTransferResources<Session>(
        usable = { it.connected },
        close = {
            it.connected = false
            it.closes++
        },
    )

    @Test
    fun `many files reuse one connection and close it only at job end`() = runBlocking {
        val resources = resources()
        var opens = 0
        val session = resources.get {
            opens++
            Session()
        }
        repeat(100) {
            assertSame(
                session,
                resources.get {
                    opens++
                    Session()
                },
            )
            assertEquals(0, session.closes)
        }
        resources.closeAll()
        resources.closeAll()
        assertEquals(1, opens)
        assertEquals(1, session.closes)
    }

    @Test
    fun `dead session is replaced and exclusive create conflicts do not require discarding it`() = runBlocking {
        val resources = resources()
        val first = resources.get { Session() }
        // A filename conflict leaves the TCP healthy.
        assertSame(first, resources.get { Session() })
        first.connected = false
        val replacement = resources.get { Session() }
        assertNotSame(first, replacement)
        assertEquals(1, first.closes)
        assertEquals(0, replacement.closes)
        resources.closeAll()
        assertEquals(1, replacement.closes)
    }

    @Test
    fun `separate jobs keep separate destination sessions and cancellation cleanup closes its own`() = runBlocking {
        val firstJob = resources()
        val secondJob = resources()
        val first = firstJob.get { Session() }
        val second = secondJob.get { Session() }
        assertNotSame(first, second)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { firstJob.closeAll() }
            }
        }
        worker.cancelAndJoin()
        assertEquals(1, first.closes)
        assertEquals(0, second.closes)
        assertSame(second, secondJob.get { Session() })
        secondJob.closeAll()
    }

    @Test
    fun `transport failure discards the failed session before the next file`() = runBlocking {
        val resources = resources()
        val first = resources.get { Session() }
        resources.discard()
        assertEquals(1, first.closes)
        val replacement = resources.get { Session() }
        assertNotSame(first, replacement)
        resources.closeAll()
        try {
            resources.get { Session() }
            fail("A finished job must not open another connection")
        } catch (_: IllegalStateException) {
            assertEquals(1, replacement.closes)
        }
    }
}
