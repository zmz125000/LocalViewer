package com.hippo.ehviewer.ui.reader

import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Test

class PageTransitionReadyTest {
    @Test
    fun queuedDestinationDoesNotStartFadeAndDecodeErrorReleasesWait() = runBlocking {
        val page = Page(5)
        val ready = async(start = CoroutineStart.UNDISPATCHED) { awaitTransitionImages(listOf(page)) }
        yield()
        assertFalse(ready.isCompleted)
        page.statusFlow.value = PageStatus.Error("decode failed")
        assertFalse(ready.await())
    }

    @Test
    fun unavailableDestinationDoesNotStartFade() = runBlocking {
        assertFalse(awaitTransitionImages(emptyList()))
    }
}
