package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingVideoTagTest {
    @Test
    fun deepFolder_isVideoNavigableLikeGallery() {
        // Library/Shows/Season1/Episode1/… — grand peek only sees another directory.
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Shows", isDirectory = true)),
            childPeeks = mapOf(
                "Shows" to listOf(RemoteChild(name = "Season1", isDirectory = true)),
            ),
            grandPeeks = mapOf(
                "Shows/Season1" to listOf(RemoteChild(name = "Episode1", isDirectory = true)),
            ),
        )
        val shows = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Shows" }
        assertEquals(DirPresence.Navigable, shows.presence)
        assertTrue(shows.hasGallery)
        assertTrue(shows.hasVideo)
        assertTrue(shows.presence.visibleIn(BrowseContentMode.Galleries, shows.hasGallery, shows.hasVideo))
        assertTrue(shows.presence.visibleIn(BrowseContentMode.Video, shows.hasGallery, shows.hasVideo))
    }

    @Test
    fun largeSubtreeWithoutGrandPeek_isVideoNavigable() {
        val seasons = (1..SMB_PROMOTE_MAX_LEAVES + 1).map { i ->
            RemoteChild(name = "Season$i", isDirectory = true)
        }
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Shows", isDirectory = true)),
            childPeeks = mapOf("Shows" to seasons),
        )
        val shows = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Shows" }
        assertEquals(DirPresence.Navigable, shows.presence)
        assertTrue(shows.hasGallery)
        assertTrue(shows.hasVideo)
    }

    @Test
    fun archiveOnlyFolder_isGalleryNavigableNotVideo() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Packs", isDirectory = true)),
            childPeeks = mapOf(
                "Packs" to listOf(RemoteChild(name = "vol1.zip", isDirectory = false)),
            ),
        )
        val packs = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Packs" }
        assertEquals(DirPresence.Navigable, packs.presence)
        assertTrue(packs.hasGallery)
        assertFalse(packs.hasVideo)
        assertTrue(packs.presence.visibleIn(BrowseContentMode.Galleries, packs.hasGallery, packs.hasVideo))
        assertFalse(packs.presence.visibleIn(BrowseContentMode.Video, packs.hasGallery, packs.hasVideo))
    }
}
