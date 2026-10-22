package com.hippo.ehviewer.library

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class BrowseThumbPreviewTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

    @Test
    fun cacheEncodingWaitsForCoilDecodeInsteadOfHoldingUpDisplay() = runBlocking {
        val events = mutableListOf<String>()
        var displayed: BrowseThumbPreview? = null
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            previewBrowseThumb(jpeg, "cover.jpg") {
                displayed = it
                events += "source available to Coil"
            }
            events += "encode disk thumb"
        }
        assertEquals(listOf("source available to Coil"), events)
        assertSame(jpeg, displayed!!.data)
        displayed!!.decoded.complete(Unit)
        writer.join()
        assertEquals(listOf("source available to Coil", "encode disk thumb"), events)
    }

    @Test
    fun scrollingAwayCancelsPendingEncode() = runBlocking {
        var encoded = false
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            previewBrowseThumb(jpeg, "cover.jpg") { }
            encoded = true
        }
        writer.cancelAndJoin()
        assertFalse(encoded)
    }

    @Test
    fun nativeSourcesAreConvertedBeforeBeingOfferedToCoil() = runBlocking {
        var published = false
        previewBrowseThumb(byteArrayOf(1), "cover.jxl") { published = true }
        assertFalse(published)
    }
}
