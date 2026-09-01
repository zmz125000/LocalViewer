package com.hippo.ehviewer.library

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipAsDirListingTest {
    @Test
    fun flatImageOnlyOpensAsArchiveReader() {
        val cd = openZip(
            "001.jpg" to byteArrayOf(1),
            "002.png" to byteArrayOf(2),
        )
        assertTrue(ZipAsDirListing.shouldOpenAsArchiveReader(cd))
        assertEquals(listOf("001.jpg", "002.png"), ZipAsDirListing.directImageNames(cd))
    }

    @Test
    fun nestedAlbumEntersAsDir() {
        val cd = openZip(
            "Album/a.jpg" to byteArrayOf(1),
            "Album/b.jpg" to byteArrayOf(2),
            "readme.txt" to byteArrayOf(3),
        )
        assertFalse(ZipAsDirListing.shouldOpenAsArchiveReader(cd))
        val root = ZipAsDirListing.classifyAt(cd, "", "comic.zip")
        assertTrue(root.any { it is BrowseEntryRemote.Directory && it.name == "Album" })
        assertTrue(root.any { it is BrowseEntryRemote.RegularFile && it.fileName == "readme.txt" })
        val album = ZipAsDirListing.classifyAt(cd, "Album", "Album")
        val gal = album.filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .first { it.relativeName.isEmpty() }
        assertEquals(2, gal.pageCount)
        assertEquals("a.jpg", gal.coverFileName)
    }

    @Test
    fun promotedLeafGalleryFromNestedTree() {
        // Parent S with one image leaf → @S gallery promote.
        val cd = openZip(
            "S/leaf/01.jpg" to byteArrayOf(1),
            "S/leaf/02.jpg" to byteArrayOf(2),
        )
        assertFalse(ZipAsDirListing.shouldOpenAsArchiveReader(cd))
        val root = ZipAsDirListing.classifyAt(cd, "", "pack.zip")
        assertTrue(
            root.any {
                it is BrowseEntryRemote.FolderGallery && it.virtual && it.relativeName == "S/leaf"
            },
        )
    }

    @Test
    fun nestedZipStaysArchiveRow() {
        val cd = openZip(
            "inner.cbz" to byteArrayOf(1, 2, 3),
            "pic.jpg" to byteArrayOf(4),
        )
        val root = ZipAsDirListing.classifyAt(cd, "", "outer.zip")
        assertTrue(root.any { it is BrowseEntryRemote.ArchiveGallery && it.fileName == "inner.cbz" })
        assertTrue(root.any { it is BrowseEntryRemote.FolderGallery && it.relativeName.isEmpty() })
        // Has non-image file → not flat comic reader shortcut.
        assertFalse(ZipAsDirListing.shouldOpenAsArchiveReader(cd))
    }

    @Test
    fun videoListedAtRoot() {
        val cd = openZip(
            "clip.mp4" to ByteArray(8),
            "folder/x.jpg" to byteArrayOf(1),
        )
        val root = ZipAsDirListing.classifyAt(cd, "", "media.zip")
        assertTrue(root.any { it is BrowseEntryRemote.VideoFile && it.fileName == "clip.mp4" })
    }

    @Test
    fun firstImageMemberUnderPrefix() {
        val cd = openZip(
            "Album/b.jpg" to byteArrayOf(1),
            "Album/a.jpg" to byteArrayOf(2),
            "other/z.jpg" to byteArrayOf(3),
        )
        assertEquals("Album/a.jpg", ZipAsDirListing.firstImageMember(cd, "Album"))
    }

    private fun openZip(vararg members: Pair<String, ByteArray>): ZipCentralDirectory {
        val file = File.createTempFile("zip-as-dir-", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, bytes) in members) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        val cd = ZipCentralDirectory.open(FileArchiveByteSource(file))
        requireNotNull(cd) { "failed to parse test zip" }
        return cd
    }
}
