package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseVirtualTest {
    @Test
    fun documentModeForcesListLikePhotoGridForcesGrid() {
        assertTrue(BrowseContentMode.Document.forceList)
        assertFalse(BrowseContentMode.Galleries.forceList)
        assertFalse(BrowseContentMode.Folder.forceList)
        assertFalse(
            browseUseGrid(
                listMode = 1,
                contentMode = BrowseContentMode.Document,
                virtual = BrowseVirtualKind.None,
            ),
        )
        assertTrue(
            browseUseGrid(
                listMode = 1,
                contentMode = BrowseContentMode.Galleries,
                virtual = BrowseVirtualKind.None,
            ),
        )
        assertTrue(
            browseUseGrid(
                listMode = 0,
                contentMode = BrowseContentMode.Document,
                virtual = BrowseVirtualKind.PhotoGrid,
            ),
        )
        assertEquals(
            browseScrollLayoutKey(1, BrowseContentMode.Folder, BrowseVirtualKind.None),
            10 + BrowseContentMode.Folder.prefValue,
        )
        assertEquals(
            browseScrollLayoutKey(1, BrowseContentMode.Document, BrowseVirtualKind.None),
            BrowseContentMode.Document.prefValue,
        )
    }

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
