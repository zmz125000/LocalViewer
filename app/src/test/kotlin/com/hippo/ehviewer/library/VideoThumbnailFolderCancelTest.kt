package com.hippo.ehviewer.library

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
}
