package com.hippo.ehviewer.smb

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSmbUploadsTest {
    private class Session(var closes: Int = 0)

    @Test
    fun concurrentJobsShareOneConnectionAndStoppingOneClosesOnlyItsHandle() = runBlocking {
        withTimeout(5000) {
            val pool = SharedSmbUploads<String, Session> { it.closes++ }
            var opens = 0
            val sessions = mutableListOf<Session>()
            val closedHandles = List(2) { CompletableDeferred<Unit>() }
            val workers = List(2) { index ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    pool.withConnection("server:445/user", {
                        opens++
                        Session()
                    }) { session ->
                        sessions += session
                        val closed = AtomicBoolean(false)
                        val handle = AutoCloseable { if (closed.compareAndSet(false, true)) closedHandles[index].complete(Unit) }
                        val active = AtomicReference<AutoCloseable?>(null)
                        val job = coroutineContext[Job]
                        val cancel = job?.closeFileOnCancelling(active)
                        armSmbFileForCancelClose(job, active, handle)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { closedHandles[index].await() }
                            cancel?.dispose()
                        }
                    }
                }
            }
            assertEquals(1, opens)
            assertSame(sessions[0], sessions[1])
            workers[0].cancelAndJoin()
            assertTrue(closedHandles[0].isCompleted)
            assertTrue(!closedHandles[1].isCompleted)
            assertTrue(workers[1].isActive)
            assertEquals(0, sessions[0].closes)
            workers[1].cancelAndJoin()
            assertTrue(closedHandles[1].isCompleted)
            assertEquals(1, sessions[0].closes)
        }
    }

    @Test
    fun differentServersOrCredentialsKeepSeparateConnections() = runBlocking {
        val pool = SharedSmbUploads<String, Session> { it.closes++ }
        pool.withConnection("host-a/user-a", { Session() }) { first ->
            for (key in listOf("host-b/user-a", "host-a/user-b")) {
                pool.withConnection(key, { Session() }) { other ->
                    assertNotSame(first, other)
                    assertEquals(0, first.closes)
                }
            }
            assertEquals(0, first.closes)
        }
    }

    @Test
    fun theLastJobClosesTheConnectionAndTheNextBatchGetsAFreshOwner() = runBlocking {
        val pool = SharedSmbUploads<String, Session> { it.closes++ }
        var first: Session? = null
        pool.withConnection("same", { Session() }) { session ->
            first = session
            pool.withConnection("same", { error("Must reuse active connection") }) { assertSame(session, it) }
            assertEquals(0, session.closes)
        }
        assertEquals(1, first?.closes)
        pool.withConnection("same", { Session() }) { assertNotSame(first, it) }
        assertEquals(1, first?.closes)
    }
}
