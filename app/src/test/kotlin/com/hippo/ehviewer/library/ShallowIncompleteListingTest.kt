package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShallowIncompleteListingTest {
    @Test
    fun allEmptyDirectories_isShallowStub() {
        val entries = listOf(
            BrowseEntryRemote.Directory(
                name = "A",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
            ),
            BrowseEntryRemote.Directory(
                name = "B",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
            ),
            BrowseEntryRemote.RegularFile(name = "readme.txt"),
        )
        assertTrue(isShallowIncompleteListing(entries))
    }

    @Test
    fun allPendingDirectories_isShallowStub() {
        val entries = listOf(
            BrowseEntryRemote.Directory(
                name = "A",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
            BrowseEntryRemote.Directory(
                name = "B",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
        )
        assertTrue(isShallowIncompleteListing(entries))
    }

    @Test
    fun mixedPendingAndEmpty_isShallowStub() {
        val entries = listOf(
            BrowseEntryRemote.Directory(
                name = "A",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
            BrowseEntryRemote.Directory(
                name = "B",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
                hidden = true,
            ),
        )
        assertTrue(isShallowIncompleteListing(entries))
    }

    @Test
    fun navigableDirectory_isNotShallowStub() {
        val entries = listOf(
            BrowseEntryRemote.Directory(
                name = "A",
                hasVideo = false,
                hasGallery = true,
                presence = DirPresence.Navigable,
            ),
            BrowseEntryRemote.Directory(
                name = "B",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
            ),
        )
        assertFalse(isShallowIncompleteListing(entries))
    }

    @Test
    fun noDirectories_isNotShallowStub() {
        val entries = listOf(
            BrowseEntryRemote.RegularFile(name = "a.jpg"),
            BrowseEntryRemote.ArchiveGallery(name = "x.zip", fileName = "x.zip"),
        )
        assertFalse(isShallowIncompleteListing(entries))
    }

    @Test
    fun shallowClassify_emitsPendingDirsWithLooseFiles() {
        val children = listOf(
            RemoteChild(name = "Comics", isDirectory = true, size = 0L, lastModifiedMs = 1L),
            RemoteChild(name = "readme.txt", isDirectory = false, size = 10L, lastModifiedMs = 2L),
            RemoteChild(name = "cover.jpg", isDirectory = false, size = 20L, lastModifiedMs = 3L),
            RemoteChild(name = "movie.mp4", isDirectory = false, size = 30L, lastModifiedMs = 4L),
        )
        val entries = classifyRemoteListing("Root", children)
        val dirs = entries.filterIsInstance<BrowseEntryRemote.Directory>()
        assertEquals(listOf("Comics"), dirs.map { it.name })
        assertEquals(DirPresence.Pending, dirs.single().presence)
        assertTrue(entries.any { it is BrowseEntryRemote.RegularFile && it.name == "readme.txt" })
        assertTrue(entries.any { it is BrowseEntryRemote.VideoFile && it.name == "movie.mp4" })
        // Pending stubs paint in every content mode (together with files).
        assertTrue(DirPresence.Pending.visibleIn(BrowseContentMode.Galleries, false, false))
        assertTrue(DirPresence.Pending.visibleIn(BrowseContentMode.Media, false, false))
        assertTrue(DirPresence.Pending.visibleIn(BrowseContentMode.Video, false, false))
        assertTrue(DirPresence.Pending.visibleIn(BrowseContentMode.Folder, false, false))
        assertTrue(isShallowIncompleteListing(entries))
    }
}
