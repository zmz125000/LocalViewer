package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.library.LOCAL_BROWSE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FILE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.SMB_BROWSE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_BROWSE_TOKEN
import com.hippo.ehviewer.ui.main.BROWSE_RECENT_PREVIEW_GRID_ROWS
import com.hippo.ehviewer.ui.main.BROWSE_RECENT_PREVIEW_LIST_ROWS
import com.hippo.ehviewer.ui.main.browseRecentPreviewLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecentHistoryTest {
    @Test
    fun dropsBrowseDirectoryPinsAndKeepsOrder() {
        val folder = item(1, LOCAL_FOLDER_TOKEN, "Album")
        val dir = item(2, LOCAL_BROWSE_TOKEN, "Notes")
        val smbDir = item(3, SMB_BROWSE_TOKEN, "Share")
        val davDir = item(4, WEBDAV_BROWSE_TOKEN, "Dav")
        val file = item(5, LOCAL_FILE_TOKEN, "clip.mp4")
        val rows = listOf(folder, dir, smbDir, davDir, file)
        assertEquals(listOf(folder, file), libraryRecentHistory(rows))
    }

    @Test
    fun queryMatchesTitleOrJapaneseTitle() {
        val en = item(1, LOCAL_FILE_TOKEN, "Summer")
        val jp = item(2, LOCAL_FOLDER_TOKEN, "Other", titleJpn = "夏の本")
        val miss = item(3, LOCAL_FILE_TOKEN, "Winter")
        val rows = listOf(en, jp, miss)
        assertEquals(listOf(jp), libraryRecentHistory(rows, "夏"))
        assertEquals(listOf(en), libraryRecentHistory(rows, "sum"))
        assertEquals(rows, libraryRecentHistory(rows, "  "))
    }

    @Test
    fun capsAtFiftyAndSkipsHiddenRows() {
        val rows = (1L..60L).map { item(it, LOCAL_FILE_TOKEN, "file-$it") }
        assertEquals(LIBRARY_RECENT_LIMIT, libraryRecentHistory(rows).size)
        assertEquals(1L, libraryRecentHistory(rows).first().gid)
        val hidden = setOf(1L, 2L, 3L)
        val shown = libraryRecentHistory(rows, hidden = { it.gid in hidden })
        assertEquals(LIBRARY_RECENT_LIMIT, shown.size)
        assertEquals(4L, shown.first().gid)
        assertTrue(shown.none { it.gid in hidden })
    }

    @Test
    fun previewIsOneGridRowAndTwoListRows() {
        assertEquals(1, BROWSE_RECENT_PREVIEW_GRID_ROWS)
        assertEquals(2, BROWSE_RECENT_PREVIEW_LIST_ROWS)
        assertEquals(4, browseRecentPreviewLimit(grid = true, gridColumnCount = 4, listColumnCount = 1))
        assertEquals(2, browseRecentPreviewLimit(grid = false, gridColumnCount = 4, listColumnCount = 1))
    }

    private fun item(gid: Long, token: String, title: String, titleJpn: String? = null) = BaseGalleryInfo(
        gid = gid,
        token = token,
        title = title,
        titleJpn = titleJpn,
    )
}
