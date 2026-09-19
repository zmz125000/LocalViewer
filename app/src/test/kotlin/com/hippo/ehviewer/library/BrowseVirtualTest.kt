package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseVirtualTest {
    @Test
    fun videoFolderLocksVideoFilterWithoutForcingGrid() {
        val kind = BrowseVirtualKind.VideoFolder
        assertTrue(kind.isVirtual)
        assertTrue(kind.hideContentModes)
        assertFalse(kind.forceGrid)
        assertTrue(kind.scrollKeyBoost != BrowseVirtualKind.PhotoGrid.scrollKeyBoost)
        assertTrue(kind.scrollKeyBoost != BrowseVirtualKind.None.scrollKeyBoost)
    }

    @Test
    fun localBrowseVirtualPrefersPhotoGridThenVideoFolder() {
        assertEquals(
            BrowseVirtualKind.PhotoGrid,
            localBrowseVirtual(photoGrid = true, videoFolder = true, zipPlainFolder = true),
        )
        assertEquals(
            BrowseVirtualKind.VideoFolder,
            localBrowseVirtual(photoGrid = false, videoFolder = true, zipPlainFolder = true),
        )
        assertEquals(
            BrowseVirtualKind.ZipPlainFolder,
            localBrowseVirtual(photoGrid = false, videoFolder = false, zipPlainFolder = true),
        )
        assertEquals(
            BrowseVirtualKind.None,
            localBrowseVirtual(photoGrid = false, videoFolder = false, zipPlainFolder = false),
        )
    }

    @Test
    fun videoFolderOverlayFrameDoesNotSetPhotoGrid() {
        val frame = BrowseSession.LocalFrame(
            rootId = 1L,
            path = "/sdcard/Videos",
            title = "Videos",
            relativePath = "Shows",
            videoFolder = true,
        )
        assertTrue(frame.videoFolder)
        assertFalse(frame.photoGrid)
        assertEquals(
            BrowseVirtualKind.VideoFolder,
            localBrowseVirtual(photoGrid = frame.photoGrid, videoFolder = frame.videoFolder),
        )
    }
}
