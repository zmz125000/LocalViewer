package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.library.LOCAL_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.LOCAL_BROWSE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FILE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.SMB_BROWSE_TOKEN
import com.hippo.ehviewer.library.SMB_FILE_TOKEN
import com.hippo.ehviewer.library.SMB_FOLDER_TOKEN
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_BROWSE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FILE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FOLDER_TOKEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareGridModeTest {
    @Test
    fun aspectRatiosMatchOffOnAllRules() {
        assertEquals(SquareGridMode.Off, SquareGridMode.fromPref(0))
        assertEquals(SquareGridMode.On, SquareGridMode.fromPref(1))
        assertEquals(SquareGridMode.All, SquareGridMode.fromPref(2))
        assertEquals(SquareGridMode.All, SquareGridMode.fromPref(99))

        // Off: ISO B for both galleries and folders/files.
        assertEquals(SquareGridMode.ISO_B_ASPECT_RATIO, SquareGridMode.Off.aspectRatio(isGallery = true), 1e-6f)
        assertEquals(SquareGridMode.ISO_B_ASPECT_RATIO, SquareGridMode.Off.aspectRatio(isGallery = false), 1e-6f)

        // On: ISO B for galleries (grid and list), square for others.
        assertEquals(SquareGridMode.ISO_B_ASPECT_RATIO, SquareGridMode.On.aspectRatio(isGallery = true), 1e-6f)
        assertEquals(SquareGridMode.SQUARE_ASPECT_RATIO, SquareGridMode.On.aspectRatio(isGallery = false), 1e-6f)

        // All: square for everything.
        assertEquals(SquareGridMode.SQUARE_ASPECT_RATIO, SquareGridMode.All.aspectRatio(isGallery = true), 1e-6f)
        assertEquals(SquareGridMode.SQUARE_ASPECT_RATIO, SquareGridMode.All.aspectRatio(isGallery = false), 1e-6f)

        // History: ISO B for both Off and On, square only for All.
        assertEquals(SquareGridMode.ISO_B_ASPECT_RATIO, SquareGridMode.Off.historyAspectRatio(), 1e-6f)
        assertEquals(SquareGridMode.ISO_B_ASPECT_RATIO, SquareGridMode.On.historyAspectRatio(), 1e-6f)
        assertEquals(SquareGridMode.SQUARE_ASPECT_RATIO, SquareGridMode.All.historyAspectRatio(), 1e-6f)

        // ISO B grid cells use folder-grid corner radius (ShapeDefaults.Medium), square keeps 20.dp.
        assertEquals(ShapeDefaults.Medium, captionBelowGridShape(SquareGridMode.ISO_B_ASPECT_RATIO))
        assertEquals(RoundedCornerShape(20.dp), captionBelowGridShape(SquareGridMode.SQUARE_ASPECT_RATIO))

        // Quick-menu toggle switches between Off and All.
        assertEquals(SquareGridMode.Off, SquareGridMode.All.toggleOffAll())
        assertEquals(SquareGridMode.All, SquareGridMode.Off.toggleOffAll())
        assertEquals(SquareGridMode.All, SquareGridMode.On.toggleOffAll())
    }

    @Test
    fun listLeadHeightMatchesAspectRatio() {
        assertEquals(56f, SquareGridMode.All.listLeadHeight(isGallery = true, width = 56.dp).value, 1e-3f)
        assertEquals(56f, SquareGridMode.On.listLeadHeight(isGallery = false, width = 56.dp).value, 1e-3f)
        assertEquals(56f * 1.41421356f, SquareGridMode.On.listLeadHeight(isGallery = true, width = 56.dp).value, 1e-2f)
        assertEquals(56f * 1.41421356f, SquareGridMode.Off.listLeadHeight(isGallery = false, width = 56.dp).value, 1e-2f)
        assertEquals(56f * 1.41421356f, SquareGridMode.Off.historyListLeadHeight(width = 56.dp).value, 1e-2f)
        assertEquals(56f * 1.41421356f, SquareGridMode.On.historyListLeadHeight(width = 56.dp).value, 1e-2f)
        assertEquals(56f, SquareGridMode.All.historyListLeadHeight(width = 56.dp).value, 1e-3f)
    }

    @Test
    fun localHistoryIsGalleryDistinguishesGalleriesFromFoldersAndFiles() {
        fun info(token: String) = BaseGalleryInfo(gid = 1L, token = token, title = "Item")

        for (token in listOf(
            LOCAL_GALLERY_TOKEN,
            LOCAL_FOLDER_TOKEN,
            LOCAL_ARCHIVE_TOKEN,
            SMB_FOLDER_TOKEN,
            SMB_ARCHIVE_TOKEN,
            WEBDAV_FOLDER_TOKEN,
            WEBDAV_ARCHIVE_TOKEN,
        )) {
            assertTrue(token, LocalHistory.isGallery(info(token)))
        }
        for (token in listOf(
            LOCAL_BROWSE_TOKEN,
            SMB_BROWSE_TOKEN,
            WEBDAV_BROWSE_TOKEN,
            LOCAL_FILE_TOKEN,
            SMB_FILE_TOKEN,
            WEBDAV_FILE_TOKEN,
        )) {
            assertFalse(token, LocalHistory.isGallery(info(token)))
        }
    }
}
