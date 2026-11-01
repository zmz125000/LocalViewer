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
    fun galleryTrashMovesWholeFolderWithUpToThreeNonImageFiles() = runBlocking {
        val images = listOf(child("1.jpg"), child("2.webp"))
        for (extraCount in 0..3) {
            val result = collectGalleryTransferFiles(gallery, true, GalleryFileAction.Trash) {
                images + (1..extraCount).map { child("sidecar-$it.txt") }
            }
            assertEquals(listOf(gallery.copy(galleryTrash = true, galleryRegularFiles = extraCount)), result)
        }
        val result = collectGalleryTransferFiles(gallery, true, GalleryFileAction.Trash) {
            images + (1..4).map { child("sidecar-$it.txt") }
        }
        assertEquals(images.map { it.copy(galleryTrash = true) }, result)
    }

    @Test
    fun galleryTrashKeepsFolderWhenAnySubfolderExists() = runBlocking {
        val image = child("1.jpg")
        for (name in listOf("nested", ".trash")) {
            assertEquals(
                listOf(image.copy(galleryTrash = true)),
                collectGalleryTransferFiles(gallery, true, GalleryFileAction.Trash) {
                    listOf(image, child(name, true))
                },
            )
        }
    }

    @Test
    fun sourceRootGalleryDeletesImagesInsteadOfTryingToMoveStorageRoot() = runBlocking {
        val image = child("1.jpg")
        assertEquals(listOf(image.copy(galleryTrash = true)), collectGalleryTransferFiles(gallery.copy(source = source.copy(path = "")), true, GalleryFileAction.Trash) { listOf(image) })
        val safRoot = TransferLocation(ExplorerWindows.Kind.Local, 0, "content:/provider/tree/primary%3APhotos/document/primary%3APhotos")
        assertEquals(listOf(image.copy(galleryTrash = true)), collectGalleryTransferFiles(gallery.copy(source = safRoot), true, GalleryFileAction.Trash) { listOf(image) })
        val safFolder = safRoot.copy(path = safRoot.path + "/album")
        val folder = gallery.copy(source = safFolder)
        assertEquals(listOf(folder.copy(galleryTrash = true)), collectGalleryTransferFiles(folder, true, GalleryFileAction.Trash) { listOf(image) })
    }

    @Test
    fun archiveTrashRetainsGalleryRemovalPolicyWithoutListing() = runBlocking {
        val archive = gallery.copy(source = source.copy(path = "photos/book.zip"))
        val result = collectGalleryTransferFiles(archive, true, GalleryFileAction.Trash) { error("Archive must not be listed") }.single()
        assertTrue(result.galleryTrash)
        assertFalse(result.directory)
        assertEquals(0, result.galleryRegularFiles)
    }

    @Test
    fun emptyGalleryTrashDoesNotMoveItsOtherFiles() = runBlocking {
        assertTrue(collectGalleryTransferFiles(gallery, true, GalleryFileAction.Trash) { listOf(child("notes.txt")) }.isEmpty())
    }

    @Test
    fun galleryUploadCreatesNamedFolderWithOnlyTheCollectedImages() = runBlocking {
        val image = child("1.jpg")
        val result = collectGalleryTransferFiles(gallery, true, GalleryFileAction.Upload) {
            listOf(image, child("notes.txt"), child("nested", true), child("clip.mp4"))
        }.single()
        assertTrue(result.directory)
        assertEquals("Album", result.relative)
        assertEquals(listOf(image), result.galleryImages)
        assertEquals(result, uploadSelectionRoots(listOf(result)).single())
        val empty = collectGalleryTransferFiles(gallery, true, GalleryFileAction.Upload) { listOf(child("notes.txt")) }
        assertTrue(empty.isEmpty())
    }

    @Test
    fun sameNamedGalleryUploadsKeepSeparateFolderRootsAndImageSnapshots() = runBlocking {
        val first = collectGalleryTransferFiles(gallery, true, GalleryFileAction.Upload) { listOf(child("1.jpg")) }.single()
        val second = first.copy(source = source.copy(path = "other/album"))
        val roots = uploadSelectionRoots(listOf(first, second))
        assertEquals(listOf("Album", "Album (2)"), roots.map { it.relative })
        assertTrue(roots.all { it.galleryImages == first.galleryImages })
    }

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
        assertTrue(actions.copy(onPhotoGrid = null).toPick()!!.gallery)
        val folder = actions.copy(kind = BrowseOverflowKind.Common).toPick()!!
        assertFalse(folder.gallery)
    }
}
