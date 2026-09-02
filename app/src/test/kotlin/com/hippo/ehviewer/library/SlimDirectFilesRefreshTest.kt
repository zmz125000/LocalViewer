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
    fun patchesSurvivingFileMetadata_withoutReclassifyKind() {
        val cached = listOf(
            BrowseEntryRemote.ArchiveGallery(
                name = "book.cbz",
                fileName = "book.cbz",
                size = 10L,
                lastModifiedMs = 100L,
            ),
            BrowseEntryRemote.RegularFile(
                name = "note.txt",
                fileName = "note.txt",
                size = 1L,
                lastModifiedMs = 50L,
            ),
        )
        val live = listOf(
            RemoteChild(name = "book.cbz", isDirectory = false, size = 99L, lastModifiedMs = 200L),
            RemoteChild(name = "note.txt", isDirectory = false, size = 2L, lastModifiedMs = 60L),
        )
        assertTrue(slimDirectFilesUnchanged(cached, live))
        val updated = replaceSlimDirectFilesFromLive(cached, live, "Parent")
        val archive = updated.filterIsInstance<BrowseEntryRemote.ArchiveGallery>().single()
        assertEquals(99L, archive.size)
        assertEquals(200L, archive.lastModifiedMs)
        val file = updated.filterIsInstance<BrowseEntryRemote.RegularFile>().single()
        assertEquals(2L, file.size)
        assertEquals(60L, file.lastModifiedMs)
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
    fun zipAsDirFileIsNotRemovedDirectoryAndNotReaddedAsArchive() {
        val cached = listOf(
            BrowseEntryRemote.Directory(
                name = "tree.zip",
                relativeName = "tree.zip",
                hasVideo = false,
                hasGallery = true,
                presence = DirPresence.Navigable,
            ),
            BrowseEntryRemote.RegularFile(name = "notes.txt", fileName = "notes.txt", size = 1L),
        )
        val live = listOf(
            RemoteChild(name = "tree.zip", isDirectory = false, size = 99L),
            RemoteChild(name = "notes.txt", isDirectory = false, size = 2L),
        )
        val plan = planRemoteDirectorySlimRefresh(cached, live)
        assertTrue("tree.zip" in plan.removedDirectoryNames)

        val zipFiles = ZipAsDirListing.zipFileNames(live)
        assertEquals(setOf("tree.zip"), zipFiles)
        val keptRemoved = plan.removedDirectoryNames - zipFiles
        assertTrue(keptRemoved.isEmpty())

        val merged = mergeRemoteDirectorySlimRefresh(
            cached,
            RemoteDirectorySlimPlan(addedDirectories = emptyList(), removedDirectoryNames = keptRemoved),
            emptyList(),
        )
        val liveForFiles = live.filterNot { it.name in zipFiles }
        val updated = replaceSlimDirectFilesFromLive(merged, liveForFiles, "Parent")
        assertTrue(
            updated.any { it is BrowseEntryRemote.Directory && it.name == "tree.zip" },
        )
        assertTrue(updated.none { it is BrowseEntryRemote.ArchiveGallery })
        val notes = updated.filterIsInstance<BrowseEntryRemote.RegularFile>().single()
        assertEquals(2L, notes.size)
    }
}
