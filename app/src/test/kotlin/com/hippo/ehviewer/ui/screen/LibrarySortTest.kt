package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_IMAGE_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.hippo.ehviewer.library.libraryImageFileId
import com.hippo.ehviewer.library.libraryVideoFileId
import com.hippo.ehviewer.library.libraryVideoFolderId
import com.hippo.ehviewer.library.stableGalleryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySortTest {
    @Test
    fun videoIdsDoNotCollideWithGalleryFolder() {
        assertNotEquals(stableGalleryId(1L, "."), libraryVideoFolderId(1L, "."))
        assertNotEquals(stableGalleryId(1L, "Album"), libraryVideoFolderId(1L, "Album"))
        assertNotEquals(libraryVideoFolderId(1L, "Album"), libraryVideoFileId(1L, "Album/a.mp4"))
        assertNotEquals(stableGalleryId(1L, "Album"), libraryImageFileId(1L, "Album/a.jpg"))
        assertNotEquals(libraryVideoFileId(1L, "Album/a.jpg"), libraryImageFileId(1L, "Album/a.jpg"))
    }

    @Test
    fun nameSortThenDateSort() {
        val a = item(1, "Beta", mtime = 10L)
        val b = item(2, "alpha", mtime = 30L)
        val c = item(3, "Gamma", mtime = 20L)
        val name = sortLibraryItems(listOf(a, b, c), LibrarySortMode.Name, recentOpen = false, emptyMap())
        assertEquals(listOf("alpha", "Beta", "Gamma"), name.map { it.title })
        val date = sortLibraryItems(listOf(a, b, c), LibrarySortMode.Date, recentOpen = false, emptyMap())
        assertEquals(listOf("alpha", "Gamma", "Beta"), date.map { it.title })
    }

    @Test
    fun lastOpenPinsAboveName() {
        val a = item(1, "Alpha", mtime = 1L)
        val b = item(2, "Beta", mtime = 2L)
        val sorted = sortLibraryItems(
            listOf(a, b),
            LibrarySortMode.Name,
            recentOpen = true,
            historyTimeByGid = mapOf(2L to 99L),
        )
        assertEquals(listOf("Beta", "Alpha"), sorted.map { it.title })
    }

    @Test
    fun videoFileLastOpenUsesLocalFileHistoryGid() {
        val file = LocalGalleryEntity(
            id = libraryVideoFileId(1L, "a.mp4"),
            rootId = 1L,
            relativePath = "a.mp4",
            title = "a.mp4",
            kind = LOCAL_GALLERY_KIND_VIDEO_FILE,
            pageCount = 0,
            coverPath = "/sdcard/a.mp4",
            contentPath = "/sdcard/a.mp4",
            mtime = 1L,
        )
        val histGid = stableGalleryId(0L, "local-file:/sdcard/a.mp4")
        assertTrue(libraryItemLastOpenTime(file, mapOf(histGid to 50L)) == 50L)
        assertTrue(
            libraryItemLastOpenTime(
                item(9, "Album", kind = LOCAL_GALLERY_KIND_VIDEO_FOLDER),
                emptyMap(),
            ) == 0L,
        )
        assertFalse(LibrarySection.fromPref(1) == LibrarySection.Galleries)
        assertEquals(LibraryVideoMode.Folders, LibraryVideoMode.fromPref(0))
        assertEquals(LibraryVideoMode.Files, LibraryVideoMode.fromPref(1))
        assertEquals(LibraryVideoMode.Folders, LibraryVideoMode.fromPref(99))
        assertEquals(LibraryPhotoMode.Folders, LibraryPhotoMode.fromPref(0))
        assertEquals(LibraryPhotoMode.Files, LibraryPhotoMode.fromPref(1))
        assertEquals(LibraryPhotoMode.Folders, LibraryPhotoMode.fromPref(99))
    }

    @Test
    fun allPhotosFlattensImagesKeepsArchivesAndZipFolders() {
        val folder = item(1, "Album", kind = LOCAL_GALLERY_KIND_FOLDER)
        val archive = item(2, "book.pdf", kind = LOCAL_GALLERY_KIND_ARCHIVE)
        val zipFolder = LocalGalleryEntity(
            id = 3,
            rootId = 1L,
            relativePath = "pack.zip",
            title = "pack.zip",
            kind = LOCAL_GALLERY_KIND_FOLDER,
            pageCount = 2,
            coverPath = null,
            contentPath = "zipfile:/sdcard/pack.zip!.",
            mtime = 0L,
        )
        val photo = LocalGalleryEntity(
            id = libraryImageFileId(1L, "Album/a.jpg"),
            rootId = 1L,
            relativePath = "Album/a.jpg",
            title = "a.jpg",
            kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
            pageCount = 0,
            coverPath = "/sdcard/Album/a.jpg",
            contentPath = "/sdcard/Album/a.jpg",
            mtime = 40L,
        )
        val items = listOf(folder, archive, zipFolder, photo)
        val folders = filterLibraryItems(items, LibrarySection.Galleries, LibraryVideoMode.Folders, LibraryPhotoMode.Folders)
        assertEquals(listOf(1L, 2L, 3L), folders.map { it.id })
        val photos = filterLibraryItems(items, LibrarySection.Galleries, LibraryVideoMode.Folders, LibraryPhotoMode.Files)
        assertEquals(setOf(2L, 3L, photo.id), photos.map { it.id }.toSet())
        assertTrue(libraryFlattenPhotos(photos, LibrarySection.Galleries, LibraryPhotoMode.Files))
        assertFalse(libraryFlattenPhotos(folders, LibrarySection.Galleries, LibraryPhotoMode.Folders))
    }

    @Test
    fun allPhotosFallsBackToFoldersBeforeImageRowsExist() {
        val folder = item(1, "Album")
        val archive = item(2, "book.zip", kind = LOCAL_GALLERY_KIND_ARCHIVE)
        val items = listOf(folder, archive)
        val photos = filterLibraryItems(items, LibrarySection.Galleries, LibraryVideoMode.Folders, LibraryPhotoMode.Files)
        assertEquals(listOf(1L, 2L), photos.map { it.id })
        assertFalse(libraryFlattenPhotos(photos, LibrarySection.Galleries, LibraryPhotoMode.Files))
    }

    @Test
    fun allPhotosSortsByDateNotName() {
        val older = LocalGalleryEntity(
            id = libraryImageFileId(1L, "b.jpg"),
            rootId = 1L,
            relativePath = "b.jpg",
            title = "b.jpg",
            kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
            pageCount = 0,
            coverPath = "/sdcard/b.jpg",
            contentPath = "/sdcard/b.jpg",
            mtime = 10L,
        )
        val newer = LocalGalleryEntity(
            id = libraryImageFileId(1L, "a.jpg"),
            rootId = 1L,
            relativePath = "a.jpg",
            title = "a.jpg",
            kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
            pageCount = 0,
            coverPath = "/sdcard/a.jpg",
            contentPath = "/sdcard/a.jpg",
            mtime = 30L,
        )
        val archive = item(2, "zeta.zip", mtime = 20L, kind = LOCAL_GALLERY_KIND_ARCHIVE)
        val sorted = sortLibraryItems(
            listOf(older, archive, newer),
            LibrarySortMode.Date,
            recentOpen = false,
            emptyMap(),
        )
        assertEquals(listOf("a.jpg", "zeta.zip", "b.jpg"), sorted.map { it.title })
    }

    @Test
    fun allPhotosReaderStartsAtTappedPageAndSkipsArchives() {
        val older = LocalGalleryEntity(
            id = libraryImageFileId(1L, "b.jpg"),
            rootId = 1L,
            relativePath = "b.jpg",
            title = "b.jpg",
            kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
            pageCount = 0,
            coverPath = "/sdcard/b.jpg",
            contentPath = "/sdcard/b.jpg",
            mtime = 10L,
        )
        val newer = LocalGalleryEntity(
            id = libraryImageFileId(1L, "a.jpg"),
            rootId = 1L,
            relativePath = "a.jpg",
            title = "a.jpg",
            kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
            pageCount = 0,
            coverPath = "/sdcard/a.jpg",
            contentPath = "/sdcard/a.jpg",
            mtime = 30L,
        )
        val archive = item(2, "zeta.zip", mtime = 20L, kind = LOCAL_GALLERY_KIND_ARCHIVE)
        val visible = listOf(newer, archive, older)
        val (paths, page) = allPhotosReaderStart(visible, older)
        assertEquals(listOf("/sdcard/a.jpg", "/sdcard/b.jpg"), paths)
        assertEquals(1, page)
    }

    private fun item(
        id: Long,
        title: String,
        mtime: Long = 0L,
        kind: Int = LOCAL_GALLERY_KIND_FOLDER,
    ) = LocalGalleryEntity(
        id = id,
        rootId = 1L,
        relativePath = title,
        title = title,
        kind = kind,
        pageCount = 1,
        coverPath = null,
        contentPath = "/sdcard/$title",
        mtime = mtime,
    )
}
