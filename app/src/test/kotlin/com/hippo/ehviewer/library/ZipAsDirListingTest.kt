package com.hippo.ehviewer.library

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
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
    fun rewriteFlatZipArchiveBecomesDirectoryAndFolderGallery() {
        val file = writeZip(
            "001.jpg" to byteArrayOf(1),
            "002.jpg" to byteArrayOf(2),
        )
        val cd = ZipCentralDirectory.open(FileArchiveByteSource(file))!!
        val archive = BrowseEntryRemote.ArchiveGallery(name = "flat.cbz", fileName = "flat.cbz")
        val rows = ZipAsDirListing.classifyZipFileAsFolderRows(cd, archive)
        val dir = rows.filterIsInstance<BrowseEntryRemote.Directory>().single()
        assertEquals("flat.cbz", dir.name)
        assertEquals(DirPresence.LeafImages, dir.presence)
        val gal = rows.filterIsInstance<BrowseEntryRemote.FolderGallery>().single()
        assertEquals("flat.cbz", gal.relativeName)
        assertEquals(2, gal.pageCount)
    }

    @Test
    fun rewriteWrapperZipArchiveBecomesDirectoryAndPromotedGallery() {
        val file = writeZip(
            "Album/a.jpg" to byteArrayOf(1),
            "Album/b.jpg" to byteArrayOf(2),
        )
        val cd = ZipCentralDirectory.open(FileArchiveByteSource(file))!!
        val archive = BrowseEntryRemote.ArchiveGallery(name = "tree.zip", fileName = "tree.zip")
        val rows = ZipAsDirListing.classifyZipFileAsFolderRows(cd, archive)
        assertTrue(rows.any { it is BrowseEntryRemote.Directory && it.name == "tree.zip" })
        val gal = rows.filterIsInstance<BrowseEntryRemote.FolderGallery>().single()
        assertEquals("tree.zip/Album", gal.relativeName)
        assertTrue(gal.virtual)
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
        val zipDir = classified.filterIsInstance<BrowseEntryRemote.Directory>()
            .single { it.name == "flat.cbz" }
        assertEquals(DirPresence.LeafImages, zipDir.presence)
        assertTrue(classified.any { it is BrowseEntryRemote.RegularFile && it.fileName == "notes.txt" })
        val folderView = classified.filterRemoteByContentMode(BrowseContentMode.Folder)
        assertTrue(folderView.any { it is BrowseEntryRemote.Directory && it.name == "flat.cbz" })
        assertTrue(folderView.none { it is BrowseEntryRemote.FolderGallery })
        val galleriesView = classified.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertTrue(
            galleriesView.any {
                it is BrowseEntryRemote.FolderGallery && it.relativeName == "flat.cbz"
            },
        )
        assertTrue(galleriesView.none { it is BrowseEntryRemote.Directory && it.name == "flat.cbz" })
    }

    @Test
    fun singleWrapperFolderZipClassifiesAsDirectoryAndPromotedGallery() {
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
        val zipDir = classified.filterIsInstance<BrowseEntryRemote.Directory>()
            .single { it.name == "tree.zip" }
        assertEquals(DirPresence.PromotedShell, zipDir.presence)
        val gal = classified.filterIsInstance<BrowseEntryRemote.FolderGallery>().single()
        assertEquals("tree.zip/Album", gal.relativeName)
        assertTrue(gal.virtual)
        assertEquals(2, gal.pageCount)
        assertEquals("a.jpg", gal.coverFileName)
        val folderView = classified.filterRemoteByContentMode(BrowseContentMode.Folder)
        assertTrue(folderView.any { it is BrowseEntryRemote.Directory && it.name == "tree.zip" })
        assertTrue(folderView.none { it is BrowseEntryRemote.FolderGallery })
        val galleriesView = classified.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertTrue(
            galleriesView.any {
                it is BrowseEntryRemote.FolderGallery && it.relativeName == "tree.zip/Album"
            },
        )
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
        assertTrue(expansion.children.any { it.name == "comic.cbz" && it.isDirectory })
        assertTrue(expansion.peeks.containsKey("comic.cbz"))
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
    fun persistFolderIndexesSavesNestedVirtualDirs() = runBlocking {
        val saved = ArrayList<String>()
        ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = "share/comics",
            interiors = mapOf(
                "pack.zip" to listOf(BrowseEntryRemote.RegularFile("Album")),
                "pack.zip/Album" to listOf(BrowseEntryRemote.RegularFile("a.jpg")),
                "notes.txt" to listOf(BrowseEntryRemote.RegularFile("x.txt")),
            ),
            save = { dir, entries ->
                saved += dir
                entries
            },
            putRam = { _, _ -> },
        )
        assertEquals(listOf("share/comics/pack.zip", "share/comics/pack.zip/Album"), saved)
    }

    @Test
    fun classifyAllVirtualFoldersStoresRootAndNestedDirs() {
        val cd = openZip(
            "Album/ch1/01.jpg" to byteArrayOf(1),
            "Album/ch1/02.jpg" to byteArrayOf(2),
            "Album/cover.jpg" to byteArrayOf(3),
            "readme.txt" to byteArrayOf(4),
        )
        val tree = ZipAsDirListing.classifyAllVirtualFolders(cd, "pack.zip")
        assertEquals(
            setOf("pack.zip", "pack.zip/Album", "pack.zip/Album/ch1"),
            tree.keys,
        )
        assertTrue(tree.getValue("pack.zip").any { it is BrowseEntryRemote.Directory && it.name == "Album" })
        assertTrue(
            tree.getValue("pack.zip/Album").any { it is BrowseEntryRemote.Directory && it.name == "ch1" },
        )
        val ch1 = tree.getValue("pack.zip/Album/ch1").filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .first { it.relativeName.isEmpty() }
        assertEquals(2, ch1.pageCount)
    }

    @Test
    fun parentSegmentsOfZipBrowsePath() {
        assertEquals(emptyList<String>(), ZipAsDirListing.parentSegmentsOfZipBrowsePath("pack.zip"))
        assertEquals(listOf("share"), ZipAsDirListing.parentSegmentsOfZipBrowsePath("share/pack.zip/Album"))
        assertEquals(null, ZipAsDirListing.parentSegmentsOfZipBrowsePath("share/Album"))
    }

    @Test
    fun parentRelativeOfZipPath() {
        assertEquals("", ZipAsDirListing.parentRelative("pack.zip"))
        assertEquals("share", ZipAsDirListing.parentRelative("share/pack.zip"))
        assertEquals("share/comics", ZipAsDirListing.parentRelative("share/comics/pack.zip"))
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
    fun materializeLocalFilePathEncodesPromotedZipMember() {
        val base = "/sdcard/Download/Quick Share".toPath()
        val path = ZipAsDirListing.materializeLocalFilePath(
            base,
            "pack.zip/Album/clip.mp4",
            zipAsDir = true,
        )
        assertEquals(
            ZipPaths.encode("/sdcard/Download/Quick Share/pack.zip", "Album/clip.mp4"),
            path.toString(),
        )
        val off = ZipAsDirListing.materializeLocalFilePath(
            base,
            "pack.zip/Album/clip.mp4",
            zipAsDir = false,
        )
        assertEquals("/sdcard/Download/Quick Share/pack.zip/Album/clip.mp4", off.toString())
        val loose = ZipAsDirListing.materializeLocalFilePath(base, "clip.mp4", zipAsDir = true)
        assertEquals("/sdcard/Download/Quick Share/clip.mp4", loose.toString())
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
            "Quick Share/pack.zip" to "农业设施实景照",
            ZipAsDirListing.parseZipGalleryRelative("Quick Share/pack.zip/农业设施实景照"),
        )
        assertEquals(
            "Quick Share/pack.zip",
            ZipAsDirListing.parentBrowseRelative("Quick Share/pack.zip|农业设施实景照"),
        )
        assertEquals(
            "Quick Share",
            ZipAsDirListing.parentBrowseRelative("Quick Share/pack.zip"),
        )
        assertEquals(
            "/abs/pack.zip" to "周朗轩ai",
            ZipPaths.parseGallery("zipfile:/abs/pack.zip!周朗轩ai"),
        )
        assertEquals(
            "/abs/pack.zip" to "",
            ZipPaths.parseGallery("zipfile:/abs/pack.zip!."),
        )
        assertEquals(
            "Quick Share/周朗轩ai.zip|周朗轩ai",
            ZipAsDirListing.historyGalleryRelative("Quick Share/周朗轩ai.zip", "周朗轩ai"),
        )
        assertEquals(
            "Quick Share/周朗轩ai.zip" to "周朗轩ai",
            ZipAsDirListing.recoverZipGalleryRelative(
                rootAbsolutePath = "/root",
                relativePath = "Quick Share/周朗轩ai",
                coverPath = "zipfile:/root/Quick Share/周朗轩ai.zip!周朗轩ai/01.jpg",
            ),
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
    fun ensureZipAsDirDirectoryRowsAddsMissingZipDir() {
        val gal = BrowseEntryRemote.FolderGallery(
            name = "园区.zip",
            relativeName = "园区.zip/园区",
            pageCount = 2,
            coverFileName = "01.jpg",
            imageFileNames = listOf("01.jpg", "02.jpg"),
        )
        val upgraded = ZipAsDirListing.ensureZipAsDirDirectoryRows(listOf(gal))
        val dir = upgraded.filterIsInstance<BrowseEntryRemote.Directory>().single {
            it.name == "园区.zip"
        }
        assertEquals(DirPresence.PromotedShell, dir.presence)
        assertEquals("园区/01.jpg", dir.coverFileName)
        assertTrue(upgraded.any { it is BrowseEntryRemote.FolderGallery })
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
