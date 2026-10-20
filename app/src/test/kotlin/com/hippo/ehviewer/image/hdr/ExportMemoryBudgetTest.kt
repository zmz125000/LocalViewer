package com.hippo.ehviewer.image.hdr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMemoryBudgetTest {
    @Test
    fun leaseCoversWorkUntilEncodeCompletes() = runBlocking {
        val budget = ExportMemoryBudget(100)
        val decoding = CompletableDeferred<Unit>()
        val finishEncode = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = launch {
            budget.withBytes(80) {
                decoding.complete(Unit)
                finishEncode.await()
            }
        }
        decoding.await()
        val second = launch { budget.withBytes(40) { secondStarted = true } }
        yield()
        assertFalse(secondStarted)
        finishEncode.complete(Unit)
        withTimeout(5000) {
            first.join()
            second.join()
        }
        assertTrue(secondStarted)
    }

    @Test
    fun smallImagesOverlapAndCancellationReleasesLease() = runBlocking {
        val budget = ExportMemoryBudget(100)
        val firstEntered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val first = launch {
            budget.withBytes(40) {
                firstEntered.complete(Unit)
                hold.await()
            }
        }
        firstEntered.await()
        budget.withBytes(40) { /* enough memory for both */ }
        first.cancelAndJoin()
        withTimeout(5000) { budget.withBytes(100) { } }
    }

    @Test
    fun unknownOrOversizedImageRunsAloneAndWaitingCancellationDoesNotLeak() = runBlocking {
        val budget = ExportMemoryBudget(100)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val large = launch {
            budget.withBytes(Long.MAX_VALUE) {
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        var ran = false
        val waiting = launch { budget.withBytes(1) { ran = true } }
        yield()
        assertFalse(ran)
        waiting.cancelAndJoin()
        finish.complete(Unit)
        large.join()
        withTimeout(5000) { budget.withBytes(100) { } }
        assertEquals(Long.MAX_VALUE, exportWorkingBytes(0, 0, 0, false))
        assertTrue(exportWorkingBytes(4000, 3000, 0, false) > 800L * 1024 * 1024)
        assertTrue(exportWorkingBytes(4000, 3000, 0, true) > exportWorkingBytes(4000, 3000, 0, false))
    }
}
