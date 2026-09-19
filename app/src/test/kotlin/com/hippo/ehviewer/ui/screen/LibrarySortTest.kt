package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
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
