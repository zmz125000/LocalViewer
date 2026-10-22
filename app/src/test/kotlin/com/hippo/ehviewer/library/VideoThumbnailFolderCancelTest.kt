package com.hippo.ehviewer.library

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbnailFolderCancelTest {
    @Test
    fun appBackgroundPausesAllExtracts() {
        VideoThumbnail.onAppForegrounded()
        assertTrue(VideoThumbnail.extractEnabled.value)
        VideoThumbnail.onAppBackgrounded()
        assertFalse(VideoThumbnail.extractEnabled.value)
        VideoThumbnail.onAppForegrounded()
        assertTrue(VideoThumbnail.extractEnabled.value)
    }

    @Test
    fun leaveFolderClearsBrowseKey() {
        VideoThumbnail.onBrowseFolderChanged("local:1:a")
        assertEquals("local:1:a", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderChanged("local:1:a")
        assertEquals("local:1:a", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderChanged("local:1:b")
        assertEquals("local:1:b", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderChanged("")
        assertEquals("", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderChanged("library:Videos:Files")
        assertEquals("library:Videos:Files", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderLeft("local:")
        assertEquals("library:Videos:Files", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderLeft("library:")
        assertEquals("", VideoThumbnail.browseFolderKeyForTest())
    }

    @Test
    fun sidePanelSwitchDoesNotClearTheNewWindow() {
        VideoThumbnail.onBrowseFolderChanged("smb:1:movies")
        VideoThumbnail.onBrowseFolderChanged("smb:2:shows")
        VideoThumbnail.onBrowseFolderLeftIfCurrent("smb:1:movies")
        assertEquals("smb:2:shows", VideoThumbnail.browseFolderKeyForTest())
        VideoThumbnail.onBrowseFolderLeftIfCurrent("smb:2:shows")
        assertEquals("", VideoThumbnail.browseFolderKeyForTest())
    }

    @Test
    fun folderChangeCancelsStaleExtract() = runBlocking {
        val key = "smb-test-${System.nanoTime()}"
        VideoThumbnail.onBrowseFolderChanged("$key-a")
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<CancellationException>()
        val job = launch(Dispatchers.Default) {
            try {
                VideoThumbnail.runExtractForTest {
                    started.complete(Unit)
                    delay(60_000)
                }
            } catch (e: CancellationException) {
                cancelled.complete(e)
                throw e
            }
        }
        withTimeout(5_000) { started.await() }
        VideoThumbnail.onBrowseFolderChanged("$key-b")
        withTimeout(5_000) { cancelled.await() }
        job.join()
        assertTrue(cancelled.isCompleted)
        assertEquals("$key-b", VideoThumbnail.browseFolderKeyForTest())
    }

    @Test
    fun sameFolderDoesNotCancelInFlightExtract() = runBlocking {
        val key = "smb-same-${System.nanoTime()}"
        VideoThumbnail.onBrowseFolderChanged(key)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            VideoThumbnail.runExtractForTest {
                started.complete(Unit)
                delay(50)
                finished.complete(Unit)
            }
        }
        withTimeout(5_000) { started.await() }
        VideoThumbnail.onBrowseFolderChanged(key)
        withTimeout(5_000) { finished.await() }
        job.await()
        assertTrue(finished.isCompleted)
        assertEquals(key, VideoThumbnail.browseFolderKeyForTest())
    }

    @Test
    fun composeExtractJobCapsConcurrentHolders() = runBlocking {
        val composeCap = 6
        BrowseThumbComposeJobs.limitOverrideForTest = composeCap
        try {
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val release = CompletableDeferred<Unit>()
            val jobs = List(12) {
                async(Dispatchers.Default) {
                    BrowseThumbComposeJobs.withJob {
                        val n = inFlight.incrementAndGet()
                        maxInFlight.updateAndGet { maxOf(it, n) }
                        release.await()
                        inFlight.decrementAndGet()
                    }
                }
            }
            withTimeout(5_000) {
                while (maxInFlight.get() < composeCap) delay(10)
            }
            delay(50)
            assertEquals(composeCap, maxInFlight.get())
            release.complete(Unit)
            jobs.forEach { it.await() }
            assertEquals(0, inFlight.get())
        } finally {
            BrowseThumbComposeJobs.limitOverrideForTest = null
        }
    }

    @Test
    fun composeExtractJobRefillsAfterWave() = runBlocking {
        // Conflated wake tokens used to collapse N completions into 1 admit.
        val composeCap = 4
        BrowseThumbComposeJobs.limitOverrideForTest = composeCap
        try {
            val wave1 = CompletableDeferred<Unit>()
            val wave1Done = AtomicInteger(0)
            val wave2Started = AtomicInteger(0)
            val releaseWave2 = CompletableDeferred<Unit>()
            val jobs = List(composeCap * 2) { index ->
                async(Dispatchers.Default) {
                    BrowseThumbComposeJobs.withJob {
                        if (index < composeCap) {
                            wave1.await()
                            wave1Done.incrementAndGet()
                        } else {
                            wave2Started.incrementAndGet()
                            releaseWave2.await()
                        }
                    }
                }
            }
            delay(50)
            wave1.complete(Unit)
            withTimeout(5_000) {
                while (wave2Started.get() < composeCap) delay(10)
            }
            assertEquals(composeCap, wave1Done.get())
            assertEquals(composeCap, wave2Started.get())
            releaseWave2.complete(Unit)
            jobs.forEach { it.await() }
        } finally {
            BrowseThumbComposeJobs.limitOverrideForTest = null
        }
    }
}
