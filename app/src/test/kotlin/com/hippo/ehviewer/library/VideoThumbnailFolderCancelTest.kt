package com.hippo.ehviewer.library

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
}
