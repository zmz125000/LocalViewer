package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerWalkTest {
    @Test
    fun `media-only scan skips directory walk after MediaStore index`() {
        assertFalse(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = true, includeArchives = false))
    }

    @Test
    fun `archive mode still walks after MediaStore to find zips`() {
        assertTrue(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = true, includeArchives = true))
    }

    @Test
    fun `startup archive scan can skip the tree walk`() {
        assertFalse(
            LibraryScanner.shouldWalkDirectories(
                mediaStoreIndexed = true,
                includeArchives = true,
                walkDirectories = false,
            ),
        )
        assertTrue(
            LibraryScanner.shouldWalkDirectories(
                mediaStoreIndexed = true,
                includeArchives = true,
                walkDirectories = true,
            ),
        )
    }

    @Test
    fun `without MediaStore the tree is always walked`() {
        assertTrue(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = false, includeArchives = false))
        assertTrue(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = false, includeArchives = true))
    }

    @Test
    fun `keepExistingArchives drops missing files`() {
        val existing = kotlin.io.path.createTempFile("keep", ".cbz").toFile()
        val gone = kotlin.io.path.createTempFile("gone", ".cbz").toFile().apply { delete() }
        try {
            val known = mapOf(
                existing.path to listOf(
                    gallery(kind = LOCAL_GALLERY_KIND_ARCHIVE, contentPath = existing.path, id = 1),
                ),
                gone.path to listOf(
                    gallery(kind = LOCAL_GALLERY_KIND_ARCHIVE, contentPath = gone.path, id = 2),
                ),
            )
            val kept = LibraryScanner.keepExistingArchives(known)
            assertEquals(listOf(1L), kept.map { it.id })
            assertTrue(LibraryScanner.archiveFileExists(existing.path))
            assertFalse(LibraryScanner.archiveFileExists(gone.path))
            assertFalse(LibraryScanner.archiveFileExists(""))
        } finally {
            existing.delete()
        }
    }

    @Test
    fun `archiveFilePath keys zip-as-dir interiors to the zip file`() {
        val zip = "/sdcard/pack.zip"
        val inner = gallery(
            kind = LOCAL_GALLERY_KIND_FOLDER,
            contentPath = ZipPaths.encode(zip, "Album"),
        )
        assertEquals(zip, LibraryScanner.archiveFilePath(inner))
        assertNull(
            LibraryScanner.archiveFilePath(
                gallery(kind = LOCAL_GALLERY_KIND_FOLDER, contentPath = "/sdcard/Album"),
            ),
        )
        assertEquals(
            "/sdcard/book.cbz",
            LibraryScanner.archiveFilePath(
                gallery(kind = LOCAL_GALLERY_KIND_ARCHIVE, contentPath = "/sdcard/book.cbz"),
            ),
        )
    }

    @Test
    fun `groupKnownArchives reuses zip interiors under the zip path`() {
        val zip = "/sdcard/pack.zip"
        val root = gallery(kind = LOCAL_GALLERY_KIND_FOLDER, contentPath = ZipPaths.encode(zip, "."), id = 1)
        val album = gallery(kind = LOCAL_GALLERY_KIND_FOLDER, contentPath = ZipPaths.encode(zip, "Album"), id = 2)
        val folder = gallery(kind = LOCAL_GALLERY_KIND_FOLDER, contentPath = "/sdcard/Album", id = 3)
        val grouped = LibraryScanner.groupKnownArchives(listOf(root, album, folder))
        assertEquals(setOf(zip), grouped.keys)
        assertEquals(listOf(1L, 2L), grouped.getValue(zip).map { it.id })
    }

    private fun gallery(
        kind: Int,
        contentPath: String,
        id: Long = 1L,
    ) = LocalGalleryEntity(
        id = id,
        rootId = 1L,
        relativePath = "rel",
        title = "t",
        kind = kind,
        pageCount = 1,
        coverPath = null,
        contentPath = contentPath,
        mtime = 0L,
    )
}
