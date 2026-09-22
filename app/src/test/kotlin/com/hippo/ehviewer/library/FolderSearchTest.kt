package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSearchTest {
    @Test
    fun nameMatchesIsSubstringIgnoreCase() {
        assertTrue(FolderSearch.nameMatches("Photo_Album", "album"))
        assertTrue(FolderSearch.nameMatches("foo.JPG", "jpg"))
        assertFalse(FolderSearch.nameMatches("foo", "bar"))
        assertFalse(FolderSearch.nameMatches("foo", "  "))
    }

    @Test
    fun nameMatchesWildcard() {
        assertTrue(FolderSearch.nameMatches("clip.mp4", "*.mp4"))
        assertTrue(FolderSearch.nameMatches("Clip.MP4", "*.mp4"))
        assertFalse(FolderSearch.nameMatches("clip.mkv", "*.mp4"))
        assertTrue(FolderSearch.nameMatches("a1b", "a?b"))
        assertFalse(FolderSearch.nameMatches("ab", "a?b"))
    }

    @Test
    fun smbSearchPatternWrapsContainsQuery() {
        assertEquals("*album*", FolderSearch.smbSearchPattern("album"))
        assertEquals("*.zip", FolderSearch.smbSearchPattern("*.zip"))
        assertEquals("foo*", FolderSearch.smbSearchPattern("foo*"))
        assertEquals("", FolderSearch.smbSearchPattern("  "))
        assertTrue(FolderSearch.isMatchAllPattern("*"))
        assertTrue(FolderSearch.isMatchAllPattern("*.*"))
        assertFalse(FolderSearch.isMatchAllPattern("*album*"))
    }

    @Test
    fun globStarPhotoMatchesContains() {
        assertTrue(FolderSearch.globMatches("Photo_Album", "*album*"))
        assertTrue(FolderSearch.globMatches("album", "*album*"))
        assertFalse(FolderSearch.globMatches("video", "*album*"))
    }

    @Test
    fun searchReturnDirJumpsToOrigin() {
        assertEquals("", FolderSearch.searchReturnDir("", "Album/Sub"))
        assertEquals("Share", FolderSearch.searchReturnDir("Share", "Share/Album/Sub"))
        assertEquals("A/B", FolderSearch.searchReturnDir("A/B", "A/B/C"))
        assertEquals(null, FolderSearch.searchReturnDir("Share", "Share"))
        assertEquals(null, FolderSearch.searchReturnDir("", ""))
        assertEquals(null, FolderSearch.searchReturnDir("Share", "Other"))
        assertEquals(null, FolderSearch.searchReturnDir("Share/Album", "Share"))
    }

    @Test
    fun openFolderTargetSkipsCurrentFolder() {
        assertEquals("", FolderSearch.openFolderTarget("a.jpg", isDirectory = false))
        assertEquals("", FolderSearch.openFolderTarget("", isDirectory = true))
        assertEquals("Album", FolderSearch.openFolderTarget("Album", isDirectory = true))
        assertEquals("Album", FolderSearch.openFolderTarget("Album/a.jpg", isDirectory = false))
        assertEquals("S/leaf", FolderSearch.openFolderTarget("S/leaf", isDirectory = true))
        assertEquals("S", FolderSearch.openFolderTarget("S/clip.mp4", isDirectory = false))
        assertEquals("docs", FolderSearch.openFolderTarget("docs/a.pdf", isDirectory = false))
        assertEquals(
            "S",
            FolderSearch.openFolderTarget("S/leaf", isDirectory = true, virtual = true),
        )
        assertEquals(
            "S",
            FolderSearch.openFolderTarget("S/leaf/clip.mp4", isDirectory = false, virtual = true),
        )
        assertEquals("S", FolderSearch.openFolderTarget("S", isDirectory = true, virtual = true))
        assertEquals(
            "S",
            FolderSearch.openFolderTarget("S/clip.mp4", isDirectory = false, virtual = true),
        )
    }

    @Test
    fun relativeFromRootStripsSearchPrefix() {
        assertEquals("a.jpg", FolderSearch.relativeFromRoot("", "a.jpg"))
        assertEquals("Album/a.jpg", FolderSearch.relativeFromRoot("Share", "Share/Album/a.jpg"))
        assertEquals("a.jpg", FolderSearch.relativeFromRoot("Share/Album", "Share/Album/a.jpg"))
        assertEquals("Album", FolderSearch.relativeFromRoot("Share", "Share/Album"))
    }

    @Test
    fun hitFromChildMapsTypes() {
        val dir = FolderSearch.hitFromChild(
            "Nested/Folder",
            RemoteChild(name = "Folder", isDirectory = true, lastModifiedMs = 9L),
            zipAsDir = false,
        )
        assertTrue(dir is BrowseEntryRemote.Directory)
        assertEquals("Nested/Folder", (dir as BrowseEntryRemote.Directory).relativeName)

        val video = FolderSearch.hitFromChild(
            "clips/a.mp4",
            RemoteChild(name = "a.mp4", isDirectory = false, size = 12L),
            zipAsDir = false,
        )
        assertTrue(video is BrowseEntryRemote.VideoFile)
        assertEquals("clips/a.mp4", (video as BrowseEntryRemote.VideoFile).fileName)

        val zipDir = FolderSearch.hitFromChild(
            "pack.zip",
            RemoteChild(name = "pack.zip", isDirectory = false, size = 3L),
            zipAsDir = true,
        )
        assertTrue(zipDir is BrowseEntryRemote.Directory)

        val archive = FolderSearch.hitFromChild(
            "docs/a.pdf",
            RemoteChild(name = "a.pdf", isDirectory = false, size = 4L),
            zipAsDir = false,
        )
        val ag = archive as BrowseEntryRemote.ArchiveGallery
        assertEquals("a.pdf", ag.fileName)
        assertEquals("docs", ag.parentRelativeName)

        val file = FolderSearch.hitFromChild(
            "Album/a.jpg",
            RemoteChild(name = "a.jpg", isDirectory = false, size = 5L),
            zipAsDir = false,
        )
        assertTrue(file is BrowseEntryRemote.RegularFile)
        assertEquals("Album/a.jpg", (file as BrowseEntryRemote.RegularFile).fileName)
        assertEquals("Album/a.jpg", file.name)
    }

    @Test
    fun folderSearchSessionKeepsSubmittedHits() {
        val key = "test:deep-search-hits"
        try {
            val ui = BrowseSession.FolderSearchUi(
                active = true,
                keyword = "album",
                submittedKeyword = "album",
                submitGeneration = 2,
            )
            assertFalse(ui.isEmpty)
            BrowseSession.putFolderSearch(key, ui)
            val got = BrowseSession.getFolderSearch(key)
            assertEquals("album", got.submittedKeyword)
            assertEquals(2, got.submitGeneration)

            val hits = listOf(BrowseEntryRemote.RegularFile(name = "Album/a.jpg", fileName = "Album/a.jpg"))
            BrowseSession.putFolderSearchHits(
                key,
                BrowseSession.FolderSearchHits(
                    submittedKeyword = "album",
                    submitGeneration = 2,
                    includeHidden = false,
                    hits = hits,
                ),
            )
            val cached = BrowseSession.cachedFolderSearchHits<BrowseEntryRemote>(
                key,
                submittedKeyword = "album",
                submitGeneration = 2,
                includeHidden = false,
            )
            assertEquals(listOf("Album/a.jpg"), cached?.map { it.name })
            assertEquals(
                null,
                BrowseSession.cachedFolderSearchHits<BrowseEntryRemote>(
                    key,
                    submittedKeyword = "album",
                    submitGeneration = 3,
                    includeHidden = false,
                ),
            )
            BrowseSession.putFolderSearch(key, BrowseSession.FolderSearchUi())
            assertTrue(BrowseSession.peekFolderSearchHits<BrowseEntryRemote>(key).isEmpty())
        } finally {
            BrowseSession.putFolderSearch(key, BrowseSession.FolderSearchUi())
            BrowseSession.clearFolderSearchHits(key)
        }
    }
}
