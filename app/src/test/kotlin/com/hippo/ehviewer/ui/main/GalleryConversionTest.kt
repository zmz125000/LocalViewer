package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryConversionTest {
    private fun actions(path: String, kind: ExplorerWindows.Kind = ExplorerWindows.Kind.Smb) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Gallery,
        onPhotoGrid = {},
        onUnsupported = {},
    ).selectable("gallery", "Album", isFolder = true, uploadSource = TransferLocation(kind, 1, path))

    @Test
    fun ordinaryFolderGalleriesEnableConversionForEverySource() {
        for (kind in ExplorerWindows.Kind.entries) {
            assertNotNull(galleryConversionRoot(actions("photos/album", kind)))
        }
        assertNull(galleryConversionRoot(actions("photos/album").copy(kind = BrowseOverflowKind.Common)))
        assertNull(galleryConversionRoot(actions("photos/album").copy(selectFolder = false)))
    }

    @Test
    fun zipGalleryRootsAndMembersDoNotExposeFolderConversion() {
        assertNull(galleryConversionRoot(actions("photos/album.zip")))
        assertNull(galleryConversionRoot(actions("photos/album.zip/inner")))
        assertNull(galleryConversionRoot(actions("zipfile:/photos/album.zip!inner", ExplorerWindows.Kind.Local)))
    }

    @Test
    fun collectedPhotosUseRegularSelectionPicksAndPreserveSourceMetadata() {
        for (kind in ExplorerWindows.Kind.entries) {
            val source = TransferLocation(kind, 1, "photos/album/a.jpg")
            val file = TransferFile(source, "a.jpg", false, 123, 456)
            val pick = galleryConversionPick(file, "gallery")!!
            assertEquals("a.jpg", pick.title)
            assertEquals(source, pick.uploadSource)
            assertEquals(123L, pick.listLine!!.sizeBytes)
            assertEquals(456L, pick.listLine!!.lastModifiedMs)
            assertNotNull(pick.convertStill)
            assertNotNull(pick.saveInto)
            assertNull(galleryConversionPick(file.copy(relative = "notes.txt"), "gallery"))
            assertNull(galleryConversionPick(file.copy(directory = true), "gallery"))
            assertNull(galleryConversionPick(file.copy(relative = "vector.svg"), "gallery")!!.convertStill)
        }
    }
}
