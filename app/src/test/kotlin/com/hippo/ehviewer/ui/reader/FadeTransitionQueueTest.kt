package com.hippo.ehviewer.ui.reader

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeTransitionQueueTest {
    @Test
    fun repeatedNextWaitsForEachFadeAndResolvesFromItsCompletedPage() = runBlocking {
        withTimeout(5_000) {
            val queue = FadeTransitionQueue()
            val started = Channel<Int>(Channel.UNLIMITED)
            val finish = Channel<Unit>()
            var page = 0
            val turns = List(3) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    queue.navigate({ page + 1 }) { target ->
                        started.send(target)
                        finish.receive()
                        page = target
                    }
                }
            }
            assertEquals(1, started.receive())
            assertTrue(started.tryReceive().isFailure)
            assertFalse(turns.first().isCancelled)
            finish.send(Unit)
            assertEquals(2, started.receive())
            assertTrue(started.tryReceive().isFailure)
            finish.send(Unit)
            assertEquals(3, started.receive())
            finish.send(Unit)
            turns.forEach { it.join() }
            assertEquals(3, page)
        }
    }

    @Test
    fun cancellingWaitingInputDoesNotInterruptActiveFade() = runBlocking {
        withTimeout(5_000) {
            val queue = FadeTransitionQueue()
            val finish = Channel<Unit>()
            var page = 0
            val active = launch(start = CoroutineStart.UNDISPATCHED) {
                queue.navigate({ 1 }) { target ->
                    finish.receive()
                    page = target
                }
            }
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                queue.navigate({ page + 1 }) { error("Cancelled input must not run") }
            }
            waiting.cancelAndJoin()
            assertFalse(active.isCancelled)
            assertFalse(active.isCompleted)
            finish.send(Unit)
            active.join()
            assertEquals(1, page)
        }
    }
}
