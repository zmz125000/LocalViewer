package com.hippo.ehviewer.library.videothumb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ByteBudgetTest {
    @Test
    fun secondLeaseWaitsForQuota() = runBlocking {
        val budget = ByteBudget(10)
        val release = CompletableDeferred<Unit>()
        val first = async { budget.withLease(8) { release.await() } }
        yield()
        var secondRan = false
        val second = async { budget.withLease(8) { secondRan = true } }
        repeat(5) { yield() }
        assertFalse(secondRan)
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondRan)
    }

    @Test
    fun growPastQuotaNeverWaits() = runBlocking {
        val budget = ByteBudget(10)
        budget.withLease(6) { a ->
            a.reserve(6)
            budget.withLease(4) { b ->
                b.reserve(4)
                try {
                    b.reserve(1)
                    fail("expected budget exhaustion")
                } catch (e: VideoThumbBudgetException) {
                    assertTrue(e.transient)
                }
            }
            a.reserve(4)
        }
    }

    @Test
    fun resetToFreesCandidateBuffers() = runBlocking {
        val budget = ByteBudget(10)
        budget.withLease(10) { lease ->
            lease.reserve(3)
            val mark = lease.mark()
            lease.reserve(7)
            lease.resetTo(mark)
            assertEquals(3L, lease.mark())
            lease.reserve(7)
        }
    }

    @Test
    fun cancelledWaiterDoesNotLeak() = runBlocking {
        val budget = ByteBudget(10)
        val release = CompletableDeferred<Unit>()
        val holder = async { budget.withLease(10) { release.await() } }
        yield()
        val waiter = async { budget.withLease(10) { } }
        yield()
        waiter.cancel()
        release.complete(Unit)
        holder.await()
        budget.withLease(10) { it.reserve(10) }
    }
}
