package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlimDirectFilesRefreshTest {
    @Test
    fun dropsStaleDirectFile_andAddsNew() {
        val cached = listOf(
            BrowseEntryRemote.Directory(
                name = "Keep",
                hasVideo = false,
                hasGallery = true,
                presence = DirPresence.Navigable,
            ),
            BrowseEntryRemote.RegularFile(name = "gone.txt", fileName = "gone.txt", size = 1L),
            BrowseEntryRemote.ArchiveGallery(name = "old.zip", fileName = "old.zip", size = 2L),
        )
        val live = listOf(
            RemoteChild(name = "Keep", isDirectory = true),
            RemoteChild(name = "new.txt", isDirectory = false, size = 3L),
            RemoteChild(name = "fresh.zip", isDirectory = false, size = 4L),
        )
        assertFalse(slimDirectFilesUnchanged(cached, live))

        val updated = replaceSlimDirectFilesFromLive(cached, live, "Parent")
        assertTrue(updated.any { it is BrowseEntryRemote.Directory && it.name == "Keep" })
        assertFalse(updated.any { it.name == "gone.txt" || it.name == "old.zip" })
        assertTrue(updated.any { it is BrowseEntryRemote.RegularFile && it.name == "new.txt" })
        assertTrue(updated.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "fresh.zip" })
    }

    @Test
    fun keepsPromotedMultiSegmentFiles() {
        val cached = listOf(
            BrowseEntryRemote.Directory(
                name = "S",
                hasVideo = true,
                hasGallery = false,
                presence = DirPresence.PromotedShell,
            ),
            BrowseEntryRemote.VideoFile(name = "@S-leaf", fileName = "S/leaf/a.mp4", size = 9L),
            BrowseEntryRemote.RegularFile(name = "stale.txt", fileName = "stale.txt"),
        )
        val live = listOf(
            RemoteChild(name = "S", isDirectory = true),
        )
        val updated = replaceSlimDirectFilesFromLive(cached, live, "Parent")
        assertTrue(
            updated.any {
                it is BrowseEntryRemote.VideoFile && it.fileName == "S/leaf/a.mp4"
            },
        )
        assertFalse(updated.any { it.name == "stale.txt" })
    }

    @Test
    fun unchangedFileNames_reportsUnchanged() {
        val cached = listOf(
            BrowseEntryRemote.RegularFile(name = "a.txt", fileName = "a.txt"),
            BrowseEntryRemote.Directory(
                name = "D",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
            ),
        )
        val live = listOf(
            RemoteChild(name = "D", isDirectory = true),
            RemoteChild(name = "a.txt", isDirectory = false, size = 99L),
        )
        assertTrue(slimDirectFilesUnchanged(cached, live))
        assertEquals(setOf("a.txt"), cachedDirectFileNames(cached))
        assertEquals(setOf("a.txt"), liveDirectFileNames(live))
    }
}
