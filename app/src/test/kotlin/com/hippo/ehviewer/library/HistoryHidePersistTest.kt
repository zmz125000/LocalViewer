package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryHidePersistTest {
    private val root = BrowseFolderId.local(1L, "")
    private val vacation = BrowseFolderId.local(1L, "photos/vacation")
    private val day = BrowseFolderId.local(1L, "photos/vacation/day")
    private val other = BrowseFolderId.local(1L, "photos/vacation2")

    @Test
    fun hideCoversFolderFilesAndDescendantsButNotASiblingPrefix() {
        val stored = setOf("${vacation.key}=${HistoryHideMode.Hide.pref}")
        assertEquals(HistoryHideMode.Hide, historyHideMode(vacation, stored))
        assertEquals(HistoryHideMode.Hide, historyHideMode(day, stored))
        assertEquals(HistoryHideMode.Off, historyHideMode(other, stored))
        assertEquals(HistoryHideMode.Off, historyHideMode(BrowseFolderId.local(1L, "photos"), stored))

        val file = smbFile(sourceId = 7L, rel = "photos/vacation/clip.mp4")
        val nested = smbFile(sourceId = 7L, rel = "photos/vacation/day/clip.mp4")
        val sibling = smbFile(sourceId = 7L, rel = "photos/vacation2/clip.mp4")
        val smbHide = setOf("${BrowseFolderId.smb(7L, "photos/vacation").key}=${HistoryHideMode.Hide.pref}")
        assertTrue(hidesFromHistoryScreen(file, smbHide, emptyMap(), emptyList()))
        assertTrue(hidesFromHistoryScreen(nested, smbHide, emptyMap(), emptyList()))
        assertFalse(hidesFromHistoryScreen(sibling, smbHide, emptyMap(), emptyList()))
        assertEquals(HistoryHideMode.Hide, historyHideMode(BrowseFolderId.smb(7L, "photos/vacation/clip.mp4"), smbHide))
    }

    @Test
    fun explicitOffOnChildOverridesParentLock() {
        val stored = setOf(
            "${vacation.key}=${HistoryHideMode.NoRecord.pref}",
            "${day.key}=${HistoryHideMode.Off.pref}",
        )
        assertEquals(HistoryHideMode.NoRecord, historyHideMode(vacation, stored))
        assertEquals(HistoryHideMode.Off, historyHideMode(day, stored))
        assertEquals(
            HistoryHideMode.NoRecord,
            historyHideMode(BrowseFolderId.local(1L, "photos/vacation/other"), stored),
        )
    }

    @Test
    fun zipInnerRowFollowsTheZipFolder() {
        val zip = BrowseFolderId.local(3L, "comics/book.zip")
        val stored = setOf("${zip.key}=${HistoryHideMode.NoRecord.pref}")
        val inner = BrowseFolderId.local(3L, "comics/book.zip|Album")
        assertEquals(HistoryHideMode.NoRecord, historyHideMode(inner, stored))
        assertEquals(HistoryHideMode.Off, historyHideMode(BrowseFolderId.local(3L, "comics"), stored))
    }

    @Test
    fun rootHideCoversTheWholeSource() {
        val stored = setOf("${root.key}=${HistoryHideMode.Hide.pref}")
        assertEquals(HistoryHideMode.Hide, historyHideMode(vacation, stored))
        assertEquals(HistoryHideMode.Hide, historyHideMode(root, stored))
    }

    @Test
    fun absolutePathUsesTheLongestRoot() {
        val roots = listOf(1L to "/storage/pics", 2L to "/storage/pics/nested")
        assertEquals(
            BrowseFolderId.local(2L, "day/a.jpg"),
            folderForAbsolutePath("/storage/pics/nested/day/a.jpg", roots),
        )
        assertEquals(
            BrowseFolderId.local(1L, "vacation/a.jpg"),
            folderForAbsolutePath("/storage/pics/vacation/a.jpg", roots),
        )
        assertNull(folderForAbsolutePath("/other/a.jpg", roots))
    }

    @Test
    fun libraryRowUsesTheLoadedPlace() {
        val place = BrowseFolderId.local(4L, "albums/trip")
        val info = BaseGalleryInfo(gid = 99L, token = LOCAL_GALLERY_TOKEN, title = "Trip")
        val stored = setOf("${place.key}=${HistoryHideMode.Hide.pref}")
        assertTrue(hidesFromHistoryScreen(info, stored, mapOf(99L to place), emptyList()))
        assertFalse(hidesFromHistoryScreen(info, stored, emptyMap(), emptyList()))
    }

    private fun smbFile(sourceId: Long, rel: String) = BaseGalleryInfo(
        gid = 1L,
        token = SMB_FILE_TOKEN,
        title = rel.substringAfterLast('/'),
        uploader = "$sourceId\u0000$rel",
    )
}
