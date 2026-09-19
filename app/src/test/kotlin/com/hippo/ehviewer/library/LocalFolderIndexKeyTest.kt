package com.hippo.ehviewer.library

import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalFolderIndexKeyTest {
    @After
    fun tearDown() {
        BrowseSession.invalidateLocalListing()
    }

    @Test
    fun folderKeyIsPerSourceNotAbsolutePath() {
        val sameRel = "Pictures/Comics"
        assertEquals(
            BrowseSession.localFolderListingKey(3L, sameRel),
            BrowseSession.localFolderListingKey(3L, "/Pictures/Comics/"),
        )
        assertEquals(
            BrowseSession.localFolderListingKey(3L, ""),
            BrowseSession.localFolderListingKey(3L, "."),
        )
        assertNotEquals(
            BrowseSession.localFolderListingKey(1L, sameRel),
            BrowseSession.localFolderListingKey(2L, sameRel),
        )
        assertNotEquals(
            BrowseSession.localFolderListingKey(1L, sameRel),
            BrowseSession.pathKey("mediastore:/$sameRel".toPath()),
        )
    }

    @Test
    fun safAndMediaStorePathsDoNotShareRamAcrossRoots() {
        val rel = "Comics"
        val mediaPath = "mediastore:/Pictures/Comics".toPath()
        val safPath = (
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3APictures%2FComics/document/primary%3APictures%2FComics"
            ).toPath()
        val fromSaf = listOf(BrowseEntryRemote.RegularFile(name = "a.srt", fileName = "a.srt"))
        val fromMedia = listOf(BrowseEntryRemote.VideoFile(name = "a.mp4", fileName = "a.mp4"))

        BrowseSession.putLocalFolderListing(10L, rel, fromSaf, sessionCurrent = true, pathAlias = safPath)
        BrowseSession.putLocalFolderListing(11L, rel, fromMedia, sessionCurrent = true, pathAlias = mediaPath)

        assertEquals(fromSaf, BrowseSession.getLocalFolderCachedListing(10L, rel)?.entries)
        assertEquals(fromMedia, BrowseSession.getLocalFolderCachedListing(11L, rel)?.entries)
        // Path aliases are last-writer-wins and must not be the browse identity.
        assertEquals(fromMedia, BrowseSession.getLocalCachedListing(BrowseSession.pathKey(mediaPath))?.entries)
        assertNull(BrowseSession.getLocalFolderCachedListing(10L, "Pictures/Comics"))
    }

    @Test
    fun diskSourceDirIsPerRootId() {
        assertEquals("local_7", FolderIndexDisk.sourceDirName("local", 7L))
        assertEquals("local_8", FolderIndexDisk.sourceDirName("local", 8L))
        assertEquals(
            FolderIndexDisk.normalizeDir("Pictures/Comics"),
            FolderIndexDisk.normalizeDir("/Pictures/Comics/"),
        )
    }

    @Test
    fun configKeyStampsModeButDoesNotChangeSourceId() {
        val media = "mediastore:/Pictures/Comics".toPath()
        val msKey = LocalFolderListing.rootConfigKey(media, preferMediaStore = true)
        val safKey = LocalFolderListing.rootConfigKey(media, preferMediaStore = false)
        assertNotEquals(msKey, safKey)
        assertEquals("local_4", FolderIndexDisk.sourceDirName("local", 4L))
    }
}
