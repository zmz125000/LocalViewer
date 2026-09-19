package com.hippo.ehviewer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalListingJobsTest {
    @After
    fun tearDown() {
        LocalListingJobs.cancelAll()
    }

    @Test
    fun callerCancelDoesNotAbortPersistJob() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val waiter = async {
            LocalListingJobs.await("local:1|Comics") {
                started.complete(Unit)
                delay(150)
                finished.complete(Unit)
                emptyList()
            }
        }
        started.await()
        waiter.cancelAndJoin()
        withTimeout(1_000) { finished.await() }
        assertTrue(finished.isCompleted)
    }

    @Test
    fun secondAwaitJoinsInFlightJob() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val first = async {
            LocalListingJobs.await("local:2|") {
                started.complete(Unit)
                delay(80)
                emptyList()
            }
        }
        started.await()
        assertTrue(LocalListingJobs.isActive("local:2|"))
        val second = LocalListingJobs.await("local:2|") {
            error("must join existing job")
        }
        first.await()
        assertTrue(second.isEmpty())
    }

    @Test
    fun startDoesNotWaitForLoader() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        LocalListingJobs.start("local:3|") {
            started.complete(Unit)
            gate.await()
            emptyList()
        }
        withTimeout(1_000) { started.await() }
        assertTrue(LocalListingJobs.isActive("local:3|"))
        gate.complete(Unit)
        Unit
    }
}
