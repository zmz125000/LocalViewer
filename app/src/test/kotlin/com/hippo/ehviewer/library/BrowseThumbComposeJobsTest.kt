package com.hippo.ehviewer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseThumbComposeJobsTest {
    @Test
    fun documentGateRefillsAfterOnePageWithoutWaitingForPeers() = runBlocking<Unit> {
        withTimeout(5_000) {
            coroutineScope {
                val started = List(8) { CompletableDeferred<Unit>() }
                val release = List(8) { CompletableDeferred<Unit>() }
                val pages = List(8) { index ->
                    async {
                        BrowseThumbComposeJobs.withJob {
                            started[index].complete(Unit)
                            release[index].await()
                            index
                        }
                    }
                }
                started.forEach { it.await() }
                val nextStarted = CompletableDeferred<Unit>()
                val next = async {
                    BrowseThumbComposeJobs.withJob { nextStarted.complete(Unit) }
                }

                release[0].complete(Unit)
                assertEquals(0, pages[0].await())
                nextStarted.await()
                assertTrue(pages.drop(1).none { it.isCompleted })

                release.drop(1).forEach { it.complete(Unit) }
                pages.forEach { it.await() }
                next.await()
            }
        }
    }

    @Test
    fun cancellingAQueuedCellDoesNotLoseDocumentCapacity() = runBlocking<Unit> {
        withTimeout(5_000) {
            coroutineScope {
                val started = List(8) { CompletableDeferred<Unit>() }
                val holders = List(8) { index ->
                    launch {
                        BrowseThumbComposeJobs.withJob {
                            started[index].complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
                started.forEach { it.await() }
                val waiting = CompletableDeferred<Unit>()
                val cancelledStarted = CompletableDeferred<Unit>()
                val cancelled = launch {
                    waiting.complete(Unit)
                    BrowseThumbComposeJobs.withJob { cancelledStarted.complete(Unit) }
                }
                waiting.await()
                cancelled.cancelAndJoin()
                assertFalse(cancelledStarted.isCompleted)

                val nextStarted = CompletableDeferred<Unit>()
                val next = launch {
                    BrowseThumbComposeJobs.withJob { nextStarted.complete(Unit) }
                }
                holders[0].cancelAndJoin()
                nextStarted.await()
                assertTrue(holders.drop(1).all { it.isActive })

                holders.drop(1).forEach { it.cancelAndJoin() }
                next.join()
            }
        }
    }
}
