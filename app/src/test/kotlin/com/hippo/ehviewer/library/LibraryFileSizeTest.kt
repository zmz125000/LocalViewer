package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_IMAGE_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LocalGalleryEntity
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFileSizeTest {
    @Test
    fun mediaStoreFilesRetainIndexedSizeAndDateInLibraryRows() {
        val rows = mutableListOf<LocalGalleryEntity>()
        val image = SafMediaStoreListing.ImageFile("Pictures/album", "a.jpg", lastModifiedMs = 123000L, size = 4567L)
        val video = SafMediaStoreListing.ImageFile("Pictures/album", "b.mp4", lastModifiedMs = 124000L, size = 9876543210L)
        LibraryScanner.emitImageFile(
            rootId = 1L,
            parentRel = "album",
            name = image.name,
            path = "mediastore:/Pictures/album/a.jpg".toPath(),
            mtime = image.lastModifiedMs,
            size = image.size,
            indexedImageFiles = mutableSetOf(),
            out = rows,
        )
        LibraryScanner.emitVideoFile(
            rootId = 1L,
            parentRel = "album",
            name = video.name,
            path = "mediastore:/Pictures/album/b.mp4".toPath(),
            mtime = video.lastModifiedMs,
            size = video.size,
            indexedVideoFiles = mutableSetOf(),
            out = rows,
        )
        assertEquals(listOf(LOCAL_GALLERY_KIND_IMAGE_FILE, LOCAL_GALLERY_KIND_VIDEO_FILE), rows.map { it.kind })
        assertEquals(listOf(4567L, 9876543210L), rows.map { it.size })
        assertEquals(listOf(123000L, 124000L), rows.map { it.mtime })
    }

    @Test
    fun safFilesRetainListingSizeAndUnknownSizeStaysZero() {
        val rows = mutableListOf<LocalGalleryEntity>()
        LibraryScanner.emitImageFile(
            rootId = 2L,
            parentRel = "",
            name = "a.png",
            path = "content:/provider/tree/photos/a.png".toPath(),
            mtime = 1000L,
            size = 2345L,
            indexedImageFiles = mutableSetOf(),
            out = rows,
        )
        LibraryScanner.emitVideoFile(
            rootId = 2L,
            parentRel = "",
            name = "b.mp4",
            path = "content:/provider/tree/photos/b.mp4".toPath(),
            mtime = 2000L,
            size = -1L,
            indexedVideoFiles = mutableSetOf(),
            out = rows,
        )
        assertEquals(listOf(2345L, 0L), rows.map { it.size })
    }
}
