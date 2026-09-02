package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlimIndexWipeTest {
    private val comics = BrowseEntryRemote.Directory(
        name = "Comics",
        hasVideo = false,
        hasGallery = true,
        presence = DirPresence.Navigable,
    )
    private val videos = BrowseEntryRemote.Directory(
        name = "Videos",
        hasVideo = true,
        hasGallery = false,
        presence = DirPresence.Navigable,
    )
    private val cached = listOf(
        comics,
        videos,
        BrowseEntryRemote.RegularFile(name = "readme.txt", fileName = "readme.txt"),
    )

    @Test
    fun emptyLiveListing_againstCachedDirs_isUntrusted() {
        assertTrue(isUntrustedSlimLiveListing(cached, emptyList()))
        val plan = planRemoteDirectorySlimRefresh(cached, emptyList())
        assertEquals(setOf("Comics", "Videos"), plan.removedDirectoryNames)
    }

    @Test
    fun liveFilesOnly_againstCachedDirs_isUntrusted() {
        val live = listOf(RemoteChild(name = "readme.txt", isDirectory = false))
        assertTrue(isUntrustedSlimLiveListing(cached, live))
    }

    @Test
    fun liveKeepsSomeDirs_isTrustedIncrementalRemoval() {
        val live = listOf(
            RemoteChild(name = "Comics", isDirectory = true),
            RemoteChild(name = "readme.txt", isDirectory = false),
        )
        assertFalse(isUntrustedSlimLiveListing(cached, live))
        val plan = planRemoteDirectorySlimRefresh(cached, live)
        assertEquals(setOf("Videos"), plan.removedDirectoryNames)
        assertTrue(plan.addedDirectories.isEmpty())
    }

    @Test
    fun emptyCache_emptyLive_isTrusted() {
        assertFalse(isUntrustedSlimLiveListing(emptyList(), emptyList()))
    }

    @Test
    fun liveZipFiles_againstCachedZipAsDirFolders_isTrusted() {
        val zipDir = BrowseEntryRemote.Directory(
            name = "album.zip",
            relativeName = "album.zip",
            hasVideo = false,
            hasGallery = true,
            presence = DirPresence.Navigable,
        )
        val cachedZips = listOf(zipDir)
        val live = listOf(RemoteChild(name = "album.zip", isDirectory = false))
        assertFalse(isUntrustedSlimLiveListing(cachedZips, live))
    }

    @Test
    fun liveZipFileOnly_againstCachedRealDirs_isUntrusted() {
        val live = listOf(RemoteChild(name = "album.zip", isDirectory = false))
        assertTrue(isUntrustedSlimLiveListing(cached, live))
    }

    @Test
    fun keepPrevious_whenNextIsEmptyOrShallow() {
        val shallow = listOf(
            BrowseEntryRemote.Directory(
                name = "Comics",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
            BrowseEntryRemote.Directory(
                name = "Videos",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
            ),
        )
        assertTrue(shouldKeepPreviousFolderIndex(cached, emptyList()))
        assertTrue(shouldKeepPreviousFolderIndex(cached, shallow))
        assertTrue(isShallowIncompleteListing(shallow))
        assertFalse(shouldKeepPreviousFolderIndex(cached, cached))
        assertFalse(shouldKeepPreviousFolderIndex(emptyList(), cached))
    }

    @Test
    fun keepPrevious_whenNextDroppedEveryDirectory() {
        val filesOnly = listOf(
            BrowseEntryRemote.RegularFile(name = "readme.txt", fileName = "readme.txt"),
        )
        assertTrue(shouldKeepPreviousFolderIndex(cached, filesOnly))
    }

    @Test
    fun selectCached_prefersCompleteDiskOverShallowRam() {
        val shallow = listOf(
            BrowseEntryRemote.Directory(
                name = "Comics",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
        )
        val selected = selectCachedFolderListing(
            ramEntries = shallow,
            ramSessionCurrent = false,
            diskEntries = cached,
        )
        assertEquals(cached, selected?.first)
        assertEquals(false, selected?.second)
    }

    @Test
    fun selectCached_keepsCompleteRamWithoutDisk() {
        val selected = selectCachedFolderListing(
            ramEntries = cached,
            ramSessionCurrent = true,
            diskEntries = null,
        )
        assertEquals(cached to true, selected)
    }
}
