package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTransferFilesTest {
    private val source = TransferLocation(ExplorerWindows.Kind.Smb, 1, "photos/album")
    private val gallery = TransferFile(source, "Album", true, 0, 0)
    private fun child(name: String, directory: Boolean = false) = TransferFile(source.child(name), name, directory, 123, 456)

    @Test
    fun galleryCollectsOnlyDirectImagesWithTheirMetadata() = runBlocking {
        var listings = 0
        val image2 = child("2.jpg")
        val image10 = child("10.webp")
        val result = collectGalleryTransferFiles(gallery, true) { folder ->
            assertEquals(source, folder)
            listings++
            listOf(image10, child("notes.txt"), child("video.mp4"), child("subfolder", true), image2)
        }
        assertEquals(1, listings)
        assertEquals(listOf(image2, image10), result)
        assertTrue(result.all { !it.directory })
    }

    @Test
    fun plainFolderStaysOneRootWithoutListingItsContents() = runBlocking {
        assertEquals(listOf(gallery), collectGalleryTransferFiles(gallery, false) { error("Unexpected listing") })
    }

    @Test
    fun emptyGalleryNeverFallsBackToTheWholeDirectory() = runBlocking {
        assertTrue(collectGalleryTransferFiles(gallery, true) { listOf(child("notes.txt"), child("nested", true)) }.isEmpty())
    }

    @Test
    fun archiveGalleryStaysOnePhysicalArchive() = runBlocking {
        val zip = gallery.copy(source = source.copy(path = "photos/album.zip"))
        val result = collectGalleryTransferFiles(zip, true) { error("Unexpected archive listing") }.single()
        assertEquals("photos/album.zip", result.source.path)
        assertFalse(result.directory)
    }

    @Test
    fun galleryFlagSurvivesSelectionSnapshot() {
        val actions = BrowseOverflowActions(
            kind = BrowseOverflowKind.Gallery,
            onPhotoGrid = {},
            onUnsupported = {},
        ).selectable("gallery", "Album", isFolder = true, uploadSource = source)
        assertTrue(actions.toPick()!!.gallery)
        val folder = actions.copy(kind = BrowseOverflowKind.Common).toPick()!!
        assertFalse(folder.gallery)
    }
}
