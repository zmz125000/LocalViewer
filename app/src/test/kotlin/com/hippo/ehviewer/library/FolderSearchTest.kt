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
}
