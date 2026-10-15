package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_IMAGE_FILE
import com.ehviewer.core.database.model.LocalGalleryEntity
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreLiveUpdateTest {
    private val root = "/storage/emulated/0/Pictures".toPath()

    @Test
    fun `numeric last segment is an item id`() {
        assertTrue(MediaStoreLiveUpdate.isMediaStoreItemId("12045"))
        assertFalse(MediaStoreLiveUpdate.isMediaStoreItemId("media"))
        assertFalse(MediaStoreLiveUpdate.isMediaStoreItemId(null))
    }

    @Test
    fun `new image creates a folder row and a file row`() {
        val planned = plan(file = image("Pictures/Comics", "002.jpg", 2_000L), folder = null, existing = null)
        assertEquals(LOCAL_GALLERY_KIND_FOLDER, planned!!.folder.kind)
        assertEquals(1, planned.folder.pageCount)
        assertEquals("002.jpg", planned.folder.coverPath!!.toPath().name)
        assertEquals(2_000L, planned.folder.mtime)
        assertEquals(LOCAL_GALLERY_KIND_IMAGE_FILE, planned.file.kind)
        assertEquals("Comics/002.jpg", planned.file.relativePath)
        assertTrue(planned.insertedNewFile)
        assertEquals("Comics", planned.folderDir)
    }

    @Test
    fun `repeat notify does not bump page count`() {
        val first = plan(file = image("Pictures", "a.jpg", 1_000L), folder = null, existing = null)!!
        val again = plan(
            file = image("Pictures", "a.jpg", 1_500L),
            folder = first.folder,
            existing = first.file,
        )!!
        assertEquals(1, again.folder.pageCount)
        assertEquals(1_500L, again.folder.mtime)
        assertFalse(again.insertedNewFile)
    }

    @Test
    fun `second file increments count and keeps the earlier cover when it sorts first`() {
        val first = plan(file = image("Pictures", "a.jpg", 1_000L), folder = null, existing = null)!!
        val second = plan(
            file = image("Pictures", "b.jpg", 3_000L),
            folder = first.folder,
            existing = null,
        )!!
        assertEquals(2, second.folder.pageCount)
        assertEquals("a.jpg", second.folder.coverPath!!.toPath().name)
        assertEquals(3_000L, second.folder.mtime)
    }

    @Test
    fun `file that sorts first becomes the cover`() {
        val first = plan(file = image("Pictures", "b.jpg", 1_000L), folder = null, existing = null)!!
        val second = plan(
            file = image("Pictures", "a.jpg", 500L),
            folder = first.folder,
            existing = null,
        )!!
        assertEquals("a.jpg", second.folder.coverPath!!.toPath().name)
    }

    @Test
    fun `sample clip and dotfile are skipped`() {
        assertNull(
            plan(
                file = MediaStoreLiveFile("Pictures", "sample-clip.mp4", 1L, video = true),
                folder = null,
                existing = null,
            ),
        )
        assertNull(
            plan(
                file = image("Pictures/.secret", "a.jpg", 1L),
                folder = null,
                existing = null,
            ),
        )
    }

    @Test
    fun `file outside the root is not a child`() {
        assertNull(SafMediaStoreListing.relativeUnderRoot("Pictures/Comics", "Download"))
        assertEquals("", SafMediaStoreListing.relativeUnderRoot("Pictures", "Pictures"))
        assertEquals("Comics", SafMediaStoreListing.relativeUnderRoot("Pictures", "Pictures/Comics"))
    }

    @Test
    fun `append keeps natural order and does not duplicate`() {
        assertEquals(listOf("a.jpg", "b.jpg"), MediaStoreLiveUpdate.appendSortedName(listOf("b.jpg"), "a.jpg"))
        val same = listOf("a.jpg")
        assertTrue(MediaStoreLiveUpdate.appendSortedName(same, "a.jpg") === same)
    }

    @Test
    fun `short index is not complete`() {
        assertFalse(MediaStoreLiveUpdate.indexIsComplete(listOf("a.jpg"), folderPageCount = 4))
        assertTrue(MediaStoreLiveUpdate.indexIsComplete(listOf("a.jpg", "b.jpg"), folderPageCount = 2))
        assertFalse(MediaStoreLiveUpdate.indexIsComplete(null, folderPageCount = 0))
    }

    private fun image(parent: String, name: String, mtime: Long) = MediaStoreLiveFile(
        parentRelative = parent,
        name = name,
        lastModifiedMs = mtime,
        video = false,
    )

    private fun plan(
        file: MediaStoreLiveFile,
        folder: LocalGalleryEntity?,
        existing: LocalGalleryEntity?,
    ): PlannedMediaRow? {
        val folderRelative = SafMediaStoreListing.relativeUnderRoot("Pictures", file.parentRelative) ?: return null
        return MediaStoreLiveUpdate.plan(
            rootId = 7L,
            rootDisplayName = "Pictures",
            safRoot = root,
            folderRelative = folderRelative,
            file = file,
            existingFolder = folder,
            existingFile = existing,
            includeHidden = false,
        )
    }
}
