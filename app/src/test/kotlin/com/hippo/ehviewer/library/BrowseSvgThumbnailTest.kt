package com.hippo.ehviewer.library

import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.directoryBrowseCover
import com.hippo.ehviewer.ui.main.isSvgThumbnail
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSvgThumbnailTest {
    @Test
    fun svgRemainsAnImageButCannotBeABrowseThumbnail() {
        for (name in listOf("logo.svg", "logo.SVGZ")) {
            assertTrue(isImageFileName(name))
            assertFalse(isThumbnailImageFileName(name))
            assertNull(directoryBrowseCover("/pictures/$name".toPath()))
            assertTrue(BrowseCover.Local("/pictures/$name".toPath()).isSvgThumbnail())
            assertTrue(BrowseCover.Smb(1, "pictures/$name").isSvgThumbnail())
            assertTrue(BrowseCover.WebDav(1, "pictures/$name").isSvgThumbnail())
            assertTrue(BrowseCover.SmbZipMember(1, "book.zip", name).isSvgThumbnail())
            assertTrue(BrowseCover.WebDavZipMember(1, "book.zip", name).isSvgThumbnail())
            val zipPath = ZipPaths.encodePath("/book.zip", "pictures/$name")
            assertTrue(BrowseCover.Local(zipPath).isSvgThumbnail())
            assertNull(directoryBrowseCover(zipPath))
        }
        assertTrue(isThumbnailImageFileName("cover.JPG"))
        assertFalse(BrowseCover.Smb(1, "pictures/cover.jpg").isSvgThumbnail())
    }

    @Test
    fun directoryCoverSkipsSvgAndChoosesRasterImage() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Pictures",
            entries = listOf(RemoteChild(name = "Album", isDirectory = true)),
            childPeeks = mapOf(
                "Album" to listOf(
                    RemoteChild(name = "00.svg", isDirectory = false),
                    RemoteChild(name = "01.jpg", isDirectory = false),
                ),
            ),
        )
        val album = entries.filterIsInstance<BrowseEntryRemote.FolderGallery>().single()
        assertEquals("01.jpg", album.coverFileName)
        assertEquals(listOf("00.svg", "01.jpg"), album.imageFileNames)
    }

    @Test
    fun svgOnlyGalleryHasNoThumbnail() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Pictures",
            entries = listOf(RemoteChild(name = "Album", isDirectory = true)),
            childPeeks = mapOf("Album" to listOf(RemoteChild(name = "logo.svg", isDirectory = false))),
        )
        assertNull(entries.filterIsInstance<BrowseEntryRemote.FolderGallery>().single().coverFileName)
    }
}
