package com.hippo.ehviewer.smb

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbArchiveProbeTest {
    @Test
    fun largeMultiplexBudgetsDoNotIncreaseConcurrentZipParsers() {
        assertEquals(1, smbZipProbeParallelism(1))
        assertEquals(2, smbZipProbeParallelism(2))
        for (budget in listOf(4, 15, 30, 45, 100, 400)) {
            assertEquals(4, smbZipProbeParallelism(budget))
        }
    }

    @Test
    fun archiveFolderLeavesThreadsAvailableForItsSeparateReadWorkers() {
        Executors.newFixedThreadPool(8).asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                withTimeout(10_000) {
                    val gate = Semaphore(smbZipProbeParallelism(45))
                    val active = AtomicInteger()
                    val peak = AtomicInteger()
                    val completed = AtomicInteger()
                    coroutineScope {
                        List(90) {
                            async(dispatcher) {
                                gate.withPermit {
                                    val count = active.incrementAndGet()
                                    peak.updateAndGet { maxOf(it, count) }
                                    try {
                                        val read = CompletableDeferred<Unit>()
                                        // Like KeepOpenSmbFileSource: parser blocks waiting for
                                        // another worker scheduled on the same IO dispatcher.
                                        launch(dispatcher) { read.complete(Unit) }
                                        runBlocking { withTimeout(3_000) { read.await() } }
                                        completed.incrementAndGet()
                                    } finally {
                                        active.decrementAndGet()
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    assertEquals(90, completed.get())
                    assertTrue(peak.get() in 1..4)
                }
            }
        }
    }
}
