package com.hippo.ehviewer.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTimerTest {
    @Test
    fun pageTurnSurvivesItsOwnViewportUpdateAndClockRestartsAfterwards() = runBlocking {
        withTimeout(5_000) {
            val viewport = MutableStateFlow(SlideshowViewport(0, blocked = false, hasPages = true))
            val ticks = Channel<Unit>()
            val started = Channel<Int>()
            val complete = Channel<Unit>()
            val clock = launch(start = CoroutineStart.UNDISPATCHED) {
                runSlideshowTimer(viewport, { ticks.receive() }) { page ->
                    viewport.value = viewport.value.copy(blocked = true)
                    started.send(page)
                    complete.receive()
                    viewport.value = viewport.value.copy(page = page + 1, blocked = false)
                }
            }
            ticks.send(Unit)
            assertEquals(0, started.receive())
            assertTrue(ticks.trySend(Unit).isFailure)
            complete.send(Unit)
            ticks.send(Unit)
            assertEquals(1, started.receive())
            clock.cancelAndJoin()
        }
    }

    @Test
    fun cancelledPageTurnRearmsCountdownWithoutRequiringPageChange() = runBlocking {
        withTimeout(5_000) {
            val viewport = MutableStateFlow(SlideshowViewport(7, blocked = false, hasPages = true))
            val ticks = Channel<Unit>()
            val started = Channel<Int>()
            val cancel = Channel<Unit>()
            val clock = launch(start = CoroutineStart.UNDISPATCHED) {
                runSlideshowTimer(viewport, { ticks.receive() }) { page ->
                    viewport.value = viewport.value.copy(blocked = true)
                    started.send(page)
                    cancel.receive()
                    viewport.value = viewport.value.copy(blocked = false)
                    throw CancellationException("Transition setting changed")
                }
            }
            ticks.send(Unit)
            assertEquals(7, started.receive())
            cancel.send(Unit)
            ticks.send(Unit)
            assertEquals(7, started.receive())
            clock.cancelAndJoin()
        }
    }

    @Test
    fun emptyGalleryAndBusyViewportDoNotStartCountdown() = runBlocking {
        withTimeout(5_000) {
            val viewport = MutableStateFlow(SlideshowViewport(0, blocked = false, hasPages = false))
            val ticks = Channel<Unit>()
            val started = Channel<Int>()
            val clock = launch(start = CoroutineStart.UNDISPATCHED) {
                runSlideshowTimer(viewport, { ticks.receive() }) { started.send(it) }
            }
            assertTrue(ticks.trySend(Unit).isFailure)
            viewport.value = viewport.value.copy(hasPages = true, blocked = true)
            assertTrue(ticks.trySend(Unit).isFailure)
            viewport.value = viewport.value.copy(blocked = false)
            ticks.send(Unit)
            assertEquals(0, started.receive())
            clock.cancelAndJoin()
        }
    }
}
