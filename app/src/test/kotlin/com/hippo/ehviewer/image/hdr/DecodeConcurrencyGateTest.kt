package com.hippo.ehviewer.image.hdr

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeConcurrencyGateTest {
    @Test
    fun concurrentExclusiveRequestsDoNotDeadlockOrOverlap() = runBlocking {
        val gate = DecodeConcurrencyGate()
        val active = AtomicInteger()
        withTimeout(5000) {
            List(64) {
                async(Dispatchers.Default) {
                    gate.run(false) {
                        assertEquals(1, active.incrementAndGet())
                        delay(1)
                        assertEquals(0, active.decrementAndGet())
                    }
                }
            }.awaitAll()
        }
        assertEquals(0, active.get())
    }

    @Test
    fun parallelDecodesOverlapAndCancelledExclusiveWaiterReleasesItsPermit() = runBlocking {
        val gate = DecodeConcurrencyGate()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = launch {
            gate.run(true) {
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        withTimeout(5000) { gate.run(true) { } }
        val waiting = launch { gate.run(false) { error("cancelled exclusive decode ran") } }
        delay(10)
        waiting.cancelAndJoin()
        finish.complete(Unit)
        first.join()
        withTimeout(5000) { gate.run(false) { } }
    }
}
