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
import org.junit.Assert.assertSame
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

    @Test
    fun progressOrderKeyChangesWhenDateOrderChanges() {
        val name = photoGridProgressOrderKey(BrowseSortMode.Name, true, listOf("a.jpg", "b.jpg"))
        val dated = photoGridProgressOrderKey(BrowseSortMode.Date, false, listOf("b.jpg", "a.jpg"))
        assertTrue(name != dated)
    }

    @Test
    fun photoGridOpensByNameUntilDateOverride() {
        data class File(val name: String, val mtime: Long)
        val files = listOf(File("b.jpg", 10L), File("a.jpg", 30L))
        val opened = files.sortedPhotoGridPages(
            BrowseSortMode.Name,
            ascending = true,
            nameOf = { it.name },
            dateOf = { it.mtime },
        )
        assertEquals(listOf("a.jpg", "b.jpg"), opened.map { it.name })
        val dated = files.sortedPhotoGridPages(
            BrowseSortMode.Date,
            ascending = false,
            nameOf = { it.name },
            dateOf = { it.mtime },
        )
        assertEquals(listOf("a.jpg", "b.jpg"), dated.map { it.name })
        val oldestFirst = files.sortedPhotoGridPages(
            BrowseSortMode.Date,
            ascending = true,
            nameOf = { it.name },
            dateOf = { it.mtime },
        )
        assertEquals(listOf("b.jpg", "a.jpg"), oldestFirst.map { it.name })
    }

    @Test
    fun photoGridSizeSortUsesBytesAndNaturalNameTies() {
        data class File(val name: String, val size: Long)
        val files = listOf(File("page10.jpg", 20L), File("page1.jpg", 100L), File("page2.jpg", 20L))
        fun sorted(ascending: Boolean) = files.sortedPhotoGridPages(
            BrowseSortMode.Size,
            ascending,
            nameOf = { it.name },
            dateOf = { 0L },
            sizeOf = { it.size },
        ).map { it.name }

        assertEquals(listOf("page2.jpg", "page10.jpg", "page1.jpg"), sorted(true))
        assertEquals(listOf("page1.jpg", "page10.jpg", "page2.jpg"), sorted(false))
    }

    @Test
    fun libraryDateOpenOrdersFolderImagesNewestFirst() {
        data class File(val name: String, val mtime: Long)
        val files = listOf(File("a.jpg", 10L), File("b.jpg", 30L), File("c.jpg", 0L))
        val dated = files.sortedFolderImages(dateSort = true, nameOf = { it.name }, dateOf = { it.mtime })
        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), dated.map { it.name })
        val named = files.sortedFolderImages(dateSort = false, nameOf = { it.name }, dateOf = { it.mtime })
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), named.map { it.name })
        val noTimes = listOf(File("b.jpg", 0L), File("a.jpg", 0L))
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            noTimes.sortedFolderImages(dateSort = true, nameOf = { it.name }, dateOf = { it.mtime }).map { it.name },
        )
    }

    @Test
    fun libraryDirectFileMtimesStayInTheOpenedFolder() {
        val rows = listOf(
            image("Album/a.jpg", 10L),
            image("Album/b.jpg", 40L),
            image("Album/Sub/c.jpg", 99L),
            image("other.jpg", 50L),
            item(9, "Album", mtime = 80L, kind = LOCAL_GALLERY_KIND_FOLDER),
        )
        val mtimes = libraryDirectFileMtimes(rows, rootId = 1L, folderRelative = "Album")
        assertEquals(mapOf("a.jpg" to 10L, "b.jpg" to 40L), mtimes)
        val names = libraryFolderImageNames(rows, 1L, "Album", dateSort = true)
        assertEquals(listOf("b.jpg", "a.jpg"), names)
        assertEquals(null, libraryFolderImageNames(rows, 1L, "missing", dateSort = true))
    }

    @Test
    fun libraryGalleryOpenDateFollowsPhotoPrefNotListSort() {
        assertFalse(libraryOpenUsesDateSort(photoSortByDate = false))
        assertTrue(libraryOpenUsesDateSort(photoSortByDate = true))
    }

    private fun image(relativePath: String, mtime: Long) = LocalGalleryEntity(
        id = libraryImageFileId(1L, relativePath),
        rootId = 1L,
        relativePath = relativePath,
        title = relativePath.substringAfterLast('/'),
        kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
        pageCount = 0,
        coverPath = "/sdcard/$relativePath",
        contentPath = "/sdcard/$relativePath",
        mtime = mtime,
    )

    @Test
    fun ramListOpensWithoutRebuildingUntilTheViewChanges() {
        LibraryListMemory.resetForTest()
        val raw = listOf(
            item(1, "Beta", mtime = 1, contentPath = "mediastore:/Beta"),
            item(2, "alpha", mtime = 2, contentPath = "mediastore:/alpha"),
        )
        val key = LibraryListKey(
            keyword = "",
            recentOpen = false,
            sortMode = LibrarySortMode.Name,
            section = LibrarySection.Galleries,
            videoMode = LibraryVideoMode.Folders,
            photoMode = LibraryPhotoMode.Folders,
            historyEpoch = 0,
        )
        LibraryListMemory.storeRaw(raw)
        val first = LibraryListMemory.prepare(raw, key, emptyMap())
        assertEquals(listOf("alpha", "Beta"), first.items.map { it.title })
        assertSame(first, LibraryListMemory.peek(raw, key))
        assertSame(first, LibraryListMemory.prepare(raw, key, emptyMap()))
        assertSame(first.items, LibraryListMemory.display(raw, key.copy(historyEpoch = 4))?.items)
        assertEquals(null, LibraryListMemory.peek(raw, key.copy(historyEpoch = 4)))
        assertEquals(null, LibraryListMemory.display(raw, key.copy(section = LibrarySection.Videos)))
    }

    @Test
    fun recentSortOfALargeListStaysInOrder() {
        val count = 20_000
        val items = List(count) { index -> item(index.toLong(), "t$index", mtime = index.toLong()) }
        val history = HashMap<Long, Long>(count)
        for (index in 0 until count) history[index.toLong()] = (count - index).toLong()
        val started = System.nanoTime()
        val sorted = sortLibraryItems(items, LibrarySortMode.Name, recentOpen = true, history)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("t0", sorted.first().title)
        assertEquals("t${count - 1}", sorted.last().title)
        assertTrue("recent sort of $count took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    private fun item(
        id: Long,
        title: String,
        mtime: Long = 0L,
        kind: Int = LOCAL_GALLERY_KIND_FOLDER,
        contentPath: String = "/sdcard/$title",
    ) = LocalGalleryEntity(
        id = id,
        rootId = 1L,
        relativePath = title,
        title = title,
        kind = kind,
        pageCount = 1,
        coverPath = null,
        contentPath = contentPath,
        mtime = mtime,
    )
}
