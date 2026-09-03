package com.hippo.ehviewer.library

import com.ehviewer.core.files.mediaStoreParentRelativeDir
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreRelativePathTest {
    @Test
    fun prefersRelativePathWhenPresent() {
        assertEquals(
            "Pictures/Comics",
            mediaStoreParentRelativeDir("Pictures/Comics/", "/storage/emulated/0/ignored/a.mp4"),
        )
        assertEquals(
            "Movies",
            mediaStoreParentRelativeDir("Movies", null),
        )
    }

    @Test
    fun derivesFolderFromDataWhenRelativePathEmpty() {
        assertEquals(
            "Movies",
            mediaStoreParentRelativeDir(null, "/storage/emulated/0/Movies/clip.mp4"),
        )
        assertEquals(
            "Pictures/Comics",
            mediaStoreParentRelativeDir("", "/sdcard/Pictures/Comics/a.mp4"),
        )
        assertEquals(
            "DCIM/Camera",
            mediaStoreParentRelativeDir(
                "  ",
                "/storage/XXXX-YYYY/DCIM/Camera/VID_001.mp4",
            ),
        )
        assertEquals("", mediaStoreParentRelativeDir(null, null))
        assertEquals("", mediaStoreParentRelativeDir("", "/storage/emulated/0/orphan.mp4"))
    }

    @Test
    fun mimeWithoutExtensionStillCountsAsBrowseVideo() {
        assertTrue(isBrowseVideoEntry("Holiday", "video/mp4"))
        assertTrue(isBrowseVideoEntry("clip.mp4", null))
        assertFalse(isBrowseVideoEntry("Holiday", "image/jpeg"))
        assertFalse(isBrowseVideoEntry("sample-preview.mp4", "video/mp4"))
    }

    @Test
    fun mimeOnlyMediaStoreRowClassifiesAsVideoFile() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Gallery",
            entries = listOf(
                RemoteChild(name = "Holiday", isDirectory = false, mimeType = "video/mp4"),
                RemoteChild(name = "clip.mp4", isDirectory = false),
            ),
            childPeeks = emptyMap(),
        )
        val videos = entries.filterIsInstance<BrowseEntryRemote.VideoFile>().map { it.name }.toSet()
        assertEquals(setOf("Holiday", "clip.mp4"), videos)
    }

    @Test
    fun safMergeKeepsMediaStoreVideosAheadOfSafDuplicates() {
        val ms = listOf(
            BrowseChild(
                name = "clip.mp4",
                isDirectory = false,
                path = "/t/clip.mp4".toPath(),
                fromMediaStore = true,
                mimeType = "video/mp4",
            ),
        )
        val saf = listOf(
            BrowseChild(
                name = "clip.mp4",
                isDirectory = false,
                path = "/t/clip.mp4".toPath(),
            ),
            BrowseChild(
                name = "book.cbz",
                isDirectory = false,
                path = "/t/book.cbz".toPath(),
            ),
        )
        val merged = SafMediaStoreListing.merge(ms, saf)
        assertEquals(listOf("clip.mp4", "book.cbz"), merged.map { it.name })
        assertTrue(merged[0].fromMediaStore)
        assertEquals("video/mp4", merged[0].mimeType)
    }
}
