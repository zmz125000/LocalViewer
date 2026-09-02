package com.hippo.ehviewer.library

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipAsDirListingTest {
    @Test
    fun flatImageOnlyClassifiesAsRootFolderGallery() {
        val cd = openZip(
            "001.jpg" to byteArrayOf(1),
            "002.png" to byteArrayOf(2),
        )
        assertEquals(listOf("001.jpg", "002.png"), ZipAsDirListing.directImageNames(cd))
        val root = ZipAsDirListing.classifyAt(cd, "", "flat.cbz")
        val gal = root.filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .first { it.relativeName.isEmpty() }
        assertEquals(2, gal.pageCount)
        assertEquals("001.jpg", gal.coverFileName)
        assertTrue(root.none { it is BrowseEntryRemote.Directory })
    }

    @Test
    fun rewriteFlatZipArchiveBecomesFolderGallery() {
        val file = writeZip(
            "001.jpg" to byteArrayOf(1),
            "002.jpg" to byteArrayOf(2),
        )
        val cd = ZipCentralDirectory.open(FileArchiveByteSource(file))!!
        val archive = BrowseEntryRemote.ArchiveGallery(name = "flat.cbz", fileName = "flat.cbz")
        val rewritten = ZipAsDirListing.classifyZipFileAsBrowseEntry(cd, archive)
        assertTrue(rewritten is BrowseEntryRemote.FolderGallery)
        val gal = rewritten as BrowseEntryRemote.FolderGallery
        assertEquals("flat.cbz", gal.relativeName)
        assertEquals(2, gal.pageCount)
    }

    @Test
    fun rewriteTreeZipArchiveBecomesDirectory() {
        val file = writeZip(
            "Album/a.jpg" to byteArrayOf(1),
            "Album/b.jpg" to byteArrayOf(2),
        )
        val cd = ZipCentralDirectory.open(FileArchiveByteSource(file))!!
        val archive = BrowseEntryRemote.ArchiveGallery(name = "tree.zip", fileName = "tree.zip")
        val rewritten = ZipAsDirListing.classifyZipFileAsBrowseEntry(cd, archive)
        assertTrue(rewritten is BrowseEntryRemote.FolderGallery)
        val gal = rewritten as BrowseEntryRemote.FolderGallery
        assertEquals("tree.zip/Album", gal.relativeName)
        assertEquals(2, gal.pageCount)
    }

    @Test
    fun nestedAlbumEntersAsDir() {
        val cd = openZip(
            "Album/a.jpg" to byteArrayOf(1),
            "Album/b.jpg" to byteArrayOf(2),
            "readme.txt" to byteArrayOf(3),
        )
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
        // Parent S with one image leaf → @S gallery promote (same DirectoryListing path).
        val cd = openZip(
            "S/leaf/01.jpg" to byteArrayOf(1),
            "S/leaf/02.jpg" to byteArrayOf(2),
        )
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
    fun parentListingWithoutExpandClassifiesZipAsArchiveGallery() {
        val children = listOf(
            RemoteChild(name = "flat.cbz", isDirectory = false, size = 10L),
            RemoteChild(name = "notes.txt", isDirectory = false, size = 1L),
        )
        val classified = classifyRemoteListing("Parent", children)
        assertTrue(
            classified.any { it is BrowseEntryRemote.ArchiveGallery && it.fileName == "flat.cbz" },
        )
    }

    @Test
    fun expandFeedsZipAsFakeFolderSoDirectoryListingDoesNotEmitArchiveGallery() {
        val cd = openZip(
            "001.jpg" to byteArrayOf(1),
            "002.jpg" to byteArrayOf(2),
        )
        val children = listOf(
            RemoteChild(name = "flat.cbz", isDirectory = false, size = 10L),
            RemoteChild(name = "notes.txt", isDirectory = false, size = 1L),
        )
        val classified = ZipAsDirListing.classifyListingWithZipAsDirs(
            currentDirName = "Parent",
            children = children,
            childPeeks = emptyMap(),
            grandPeeks = emptyMap(),
        ) { name ->
            if (name == "flat.cbz") ZipAsDirListing.zipRootListingFromCd(cd) else null
        }
        assertTrue(
            classified.none { it is BrowseEntryRemote.ArchiveGallery && it.fileName == "flat.cbz" },
        )
        assertTrue(
            classified.any {
                it is BrowseEntryRemote.FolderGallery && it.relativeName == "flat.cbz"
            },
        )
        assertTrue(
            classified.none { it is BrowseEntryRemote.Directory && it.name == "flat.cbz" },
        )
        assertTrue(classified.any { it is BrowseEntryRemote.RegularFile && it.fileName == "notes.txt" })
    }

    @Test
    fun singleWrapperFolderZipClassifiesAsGalleryNotDirectory() {
        val cd = openZip(
            "Album/a.jpg" to byteArrayOf(1),
            "Album/b.jpg" to byteArrayOf(2),
        )
        val children = listOf(RemoteChild(name = "tree.zip", isDirectory = false))
        val classified = ZipAsDirListing.classifyListingWithZipAsDirs(
            currentDirName = "Parent",
            children = children,
            childPeeks = emptyMap(),
            grandPeeks = emptyMap(),
        ) { ZipAsDirListing.zipRootListingFromCd(cd) }
        assertTrue(classified.none { it is BrowseEntryRemote.ArchiveGallery })
        assertTrue(classified.none { it is BrowseEntryRemote.Directory && it.name == "tree.zip" })
        val gal = classified.filterIsInstance<BrowseEntryRemote.FolderGallery>().single()
        assertEquals("tree.zip/Album", gal.relativeName)
        assertEquals(2, gal.pageCount)
        assertEquals("a.jpg", gal.coverFileName)
    }

    @Test
    fun expandTreeZipClassifiesAsDirectoryNotArchive() {
        val cd = openZip(
            "Album/a.jpg" to byteArrayOf(1),
            "Extra/b.jpg" to byteArrayOf(2),
        )
        val children = listOf(RemoteChild(name = "tree.zip", isDirectory = false))
        val classified = ZipAsDirListing.classifyListingWithZipAsDirs(
            currentDirName = "Parent",
            children = children,
            childPeeks = emptyMap(),
            grandPeeks = emptyMap(),
        ) { ZipAsDirListing.zipRootListingFromCd(cd) }
        assertTrue(classified.none { it is BrowseEntryRemote.ArchiveGallery })
        assertTrue(
            classified.any { it is BrowseEntryRemote.Directory && it.name == "tree.zip" },
        )
    }

    @Test
    fun rarTarAndSevenZipAreNotZipAsDir() {
        val children = listOf(
            RemoteChild(name = "comic.rar", isDirectory = false),
            RemoteChild(name = "comic.cbr", isDirectory = false),
            RemoteChild(name = "comic.7z", isDirectory = false),
            RemoteChild(name = "comic.tar", isDirectory = false),
            RemoteChild(name = "comic.cbt", isDirectory = false),
            RemoteChild(name = "comic.pdf", isDirectory = false),
            RemoteChild(name = "comic.cbz", isDirectory = false),
        )
        val expansion = ZipAsDirListing.expandZipFilesAsFakeFolders(children) { name ->
            check(name == "comic.cbz") { "zip-as-dir must not open $name" }
            ZipAsDirListing.ZipRootListing(
                children = listOf(RemoteChild(name = "a.jpg", isDirectory = false)),
                grandPeeks = emptyMap(),
            )
        }
        assertTrue(expansion.children.filter { it.name != "comic.cbz" }.all { !it.isDirectory })
        assertTrue(expansion.galleryListings.containsKey("comic.cbz"))
        assertTrue(expansion.peeks.isEmpty())
    }

    @Test
    fun persistFolderIndexesSkipsNonZipNames() = runBlocking {
        val saved = ArrayList<String>()
        ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = "share",
            interiors = mapOf(
                "pack.zip" to listOf(BrowseEntryRemote.RegularFile("a.jpg")),
                "pack.rar" to listOf(BrowseEntryRemote.RegularFile("b.jpg")),
            ),
            save = { dir, entries ->
                saved += dir
                entries
            },
            putRam = { _, _ -> },
        )
        assertEquals(listOf("share/pack.zip"), saved)
    }

    @Test
    fun virtualRelativeDirJoinsZipAndInner() {
        assertEquals("share/pack.zip", ZipAsDirListing.virtualRelativeDir("share/pack.zip", ""))
        assertEquals(
            "share/pack.zip/Album",
            ZipAsDirListing.virtualRelativeDir("share/pack.zip", "Album"),
        )
        assertEquals(
            "share/pack.zip" to "Album",
            ZipAsDirListing.splitZipBrowsePath("share/pack.zip/Album"),
        )
    }

    @Test
    fun zipFileSegmentAndInnerPrefix() {
        assertEquals("tree.zip", ZipAsDirListing.zipFileSegment("tree.zip/Album"))
        assertEquals("Album", ZipAsDirListing.zipInnerPrefix("tree.zip/Album"))
        assertEquals("", ZipAsDirListing.zipInnerPrefix("flat.cbz"))
        assertEquals(null, ZipAsDirListing.zipFileSegment("@tree.zip"))
    }

    @Test
    fun cachedZipAsDirNamesIncludesWrapperFolderGallery() {
        val wrapper = BrowseEntryRemote.FolderGallery(
            name = "园区.zip",
            relativeName = "园区.zip/园区",
            pageCount = 2,
            coverFileName = "01.jpg",
            imageFileNames = listOf("01.jpg", "02.jpg"),
        )
        val flat = BrowseEntryRemote.FolderGallery(
            name = "flat.cbz",
            relativeName = "flat.cbz",
            pageCount = 1,
            coverFileName = "a.jpg",
            imageFileNames = listOf("a.jpg"),
        )
        val dir = BrowseEntryRemote.Directory(
            name = "tree.zip",
            relativeName = "tree.zip",
            hasVideo = false,
            hasGallery = true,
            presence = DirPresence.Navigable,
        )
        val names = ZipAsDirListing.cachedDirectZipAsDirNames(listOf(wrapper, flat, dir))
        assertTrue("园区.zip" in names)
        assertTrue("flat.cbz" in names)
        assertTrue("tree.zip" in names)
    }

    @Test
    fun splitZipBrowsePathFindsFirstZipSegment() {
        assertEquals(
            "share/pack.zip" to "Album",
            ZipAsDirListing.splitZipBrowsePath("share/pack.zip/Album"),
        )
        assertEquals(
            "pack.cbz" to "",
            ZipAsDirListing.splitZipBrowsePath("pack.cbz"),
        )
        assertEquals(null, ZipAsDirListing.splitZipBrowsePath("share/Album"))
        assertEquals(
            "dir/file.zip" to "Album",
            ZipAsDirListing.parseZipGalleryRelative("dir/file.zip|Album"),
        )
        assertEquals(
            "PDFs.zip" to "PDFs/manual.pdf",
            ZipAsDirListing.splitZipBrowsePath("PDFs.zip/PDFs/manual.pdf"),
        )
    }

    @Test
    fun zipAsDirCoverPartsFromParentListing() {
        val parts = ZipAsDirListing.zipAsDirCoverParts(
            listedDir = "share",
            relativeName = "tree.zip/Album",
            coverFileName = "a.jpg",
        )
        assertEquals("share/tree.zip" to "Album/a.jpg", parts)
    }

    @Test
    fun unreadableZipStaysFile() {
        val children = listOf(RemoteChild(name = "broken.zip", isDirectory = false))
        val expansion = ZipAsDirListing.expandZipFilesAsFakeFolders(children) { null }
        assertTrue(expansion.children.single().let { !it.isDirectory && it.name == "broken.zip" })
        assertTrue(expansion.peeks.isEmpty())
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

    private fun writeZip(vararg members: Pair<String, ByteArray>): File {
        val file = File.createTempFile("zip-as-dir-", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, bytes) in members) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun openZip(vararg members: Pair<String, ByteArray>): ZipCentralDirectory {
        val cd = ZipCentralDirectory.open(FileArchiveByteSource(writeZip(*members)))
        requireNotNull(cd) { "failed to parse test zip" }
        return cd
    }
}
