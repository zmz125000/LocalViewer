package com.hippo.ehviewer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveCoverFolderCancelTest {
    @Test
    fun folderChangeCancelsStaleCoverExtract() = runBlocking {
        val key = "test-${System.nanoTime()}"
        ArchiveCoverCache.onBrowseFolderChanged("$key-a")
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<CancellationException>()
        val job = launch(Dispatchers.Default) {
            try {
                ArchiveCoverCache.runCoverExtractForTest {
                    started.complete(Unit)
                    delay(60_000)
                }
            } catch (e: CancellationException) {
                cancelled.complete(e)
                throw e
            }
        }
        withTimeout(5_000) { started.await() }
        ArchiveCoverCache.onBrowseFolderChanged("$key-b")
        withTimeout(5_000) { cancelled.await() }
        job.join()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun sameFolderKeyDoesNotCancelInFlightCover() = runBlocking {
        val key = "test-same-${System.nanoTime()}"
        ArchiveCoverCache.onBrowseFolderChanged(key)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            ArchiveCoverCache.runCoverExtractForTest {
                started.complete(Unit)
                delay(50)
                finished.complete(Unit)
            }
        }
        withTimeout(5_000) { started.await() }
        ArchiveCoverCache.onBrowseFolderChanged(key)
        withTimeout(5_000) { finished.await() }
        job.await()
        assertTrue(finished.isCompleted)
    }
}
