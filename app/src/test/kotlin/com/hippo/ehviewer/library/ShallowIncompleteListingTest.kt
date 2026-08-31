package com.hippo.ehviewer.library

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
}
