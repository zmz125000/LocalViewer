package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.library.BrowseContentMode
import com.hippo.ehviewer.library.BrowseFolderId
import com.hippo.ehviewer.library.BrowseSquareGridPersist
import com.hippo.ehviewer.library.BrowseVirtualKind
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
import com.hippo.ehviewer.library.browseScrollLayoutKey
import com.hippo.ehviewer.library.browseUseGrid
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
    fun tabletListThumbUsesLargerDisplaySize() {
        assertEquals(CoverThumb.ListDisplay, CoverThumb.listDisplay(smallestWidthDp = 411))
        assertEquals(CoverThumb.ListDisplay, CoverThumb.listDisplay(smallestWidthDp = 599))
        assertEquals(CoverThumb.TabletListDisplay, CoverThumb.listDisplay(smallestWidthDp = 600))
        assertEquals(CoverThumb.TabletListDisplay, CoverThumb.listDisplay(smallestWidthDp = 840))
        assertTrue(CoverThumb.TabletListDisplay > CoverThumb.ListDisplay)
        assertEquals(
            CoverThumb.TabletListDisplay.value * 1.41421356f,
            SquareGridMode.Off.listLeadHeight(isGallery = true, width = CoverThumb.TabletListDisplay).value,
            1e-2f,
        )
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

    @Test
    fun folderSquareGridLockAppliesOffToFolderAndSubfolders() {
        val parent = BrowseFolderId.local(1L, "Comics")
        val child = BrowseFolderId.local(1L, "Comics/Series/Vol1")
        val sibling = BrowseFolderId.local(1L, "Photos")
        val saved = setOf(parent.key)

        val parentMatch = BrowseSquareGridPersist.resolveLocked(parent, saved)!!
        assertEquals(SquareGridMode.Off, parentMatch.saved)
        assertEquals(SquareGridMode.Off, parentMatch.effective)
        assertTrue(parentMatch.ownedHere)
        assertTrue(parentMatch.showLock)

        val childMatch = BrowseSquareGridPersist.resolveLocked(child, saved)!!
        assertEquals(parent.key, childMatch.governingKey)
        assertEquals(SquareGridMode.Off, childMatch.effective)
        assertFalse(childMatch.ownedHere)
        assertFalse(childMatch.showLock)

        // Sibling outside the locked tree is unaffected.
        assertEquals(null, BrowseSquareGridPersist.resolveLocked(sibling, saved))

        // Session RAM override temporarily switches the locked tree to All and hides the lock icon.
        val overridden = BrowseSquareGridPersist.resolveLocked(
            parent,
            saved,
            overrides = mapOf(parent.key to SquareGridMode.All),
        )!!
        assertEquals(SquareGridMode.All, overridden.effective)
        assertFalse(overridden.showLock)

        // Skipped ancestor keys (e.g. SMB RPC share root) do not govern real share paths.
        val smbRoot = BrowseFolderId.smb(2L, "")
        val smbShare = BrowseFolderId.smb(2L, "Books")
        assertEquals(
            null,
            BrowseSquareGridPersist.resolveLocked(
                smbShare,
                savedKeys = setOf(smbRoot.key),
                skipAncestorKeys = setOf(smbRoot.key),
            ),
        )
    }

    @Test
    fun galleryGridCaptionUsesThreeLinesOnlyInIsoBMode() {
        assertEquals(3, gridCaptionMaxLines(isGallery = true, aspectRatio = SquareGridMode.ISO_B_ASPECT_RATIO))
        assertEquals(2, gridCaptionMaxLines(isGallery = true, aspectRatio = SquareGridMode.SQUARE_ASPECT_RATIO))
        assertEquals(2, gridCaptionMaxLines(isGallery = false, aspectRatio = SquareGridMode.ISO_B_ASPECT_RATIO))
        assertEquals(2, gridCaptionMaxLines(isGallery = false, aspectRatio = SquareGridMode.SQUARE_ASPECT_RATIO))
    }

    @Test
    fun documentModeSwitchesToGridWhenSquareGridLockedOff() {
        // Default Document mode forces list even when listMode == 1 (grid).
        assertFalse(browseUseGrid(listMode = 1, BrowseContentMode.Document, BrowseVirtualKind.None, squareGridLocked = false))
        assertFalse(browseUseGrid(listMode = 0, BrowseContentMode.Document, BrowseVirtualKind.None, squareGridLocked = false))

        // With square-grid Off lock active, Document mode switches to B-paper grid regardless of listMode.
        assertTrue(browseUseGrid(listMode = 0, BrowseContentMode.Document, BrowseVirtualKind.None, squareGridLocked = true))
        assertTrue(browseUseGrid(listMode = 1, BrowseContentMode.Document, BrowseVirtualKind.None, squareGridLocked = true))

        // Scroll layout key distinguishes Document list vs Document locked B-paper grid.
        val listKey = browseScrollLayoutKey(listMode = 0, BrowseContentMode.Document, BrowseVirtualKind.None, squareGridLocked = false)
        val gridKey = browseScrollLayoutKey(listMode = 0, BrowseContentMode.Document, BrowseVirtualKind.None, squareGridLocked = true)
        assertTrue(listKey != gridKey)

        // Non-Document modes still follow listMode.
        assertFalse(browseUseGrid(listMode = 0, BrowseContentMode.Media, BrowseVirtualKind.None, squareGridLocked = true))
        assertTrue(browseUseGrid(listMode = 1, BrowseContentMode.Media, BrowseVirtualKind.None, squareGridLocked = true))
    }
}
