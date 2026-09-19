package com.hippo.ehviewer.ui.reader

import com.hippo.ehviewer.library.ZipPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPhotoGridTest {
    @Test
    fun folderGalleriesSupportPhotoGrid() {
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.LocalFolder("/sdcard/Album")))
        assertTrue(
            readerGallerySupportsPhotoGrid(
                ReaderScreenArgs.LocalZipFolder("/sdcard/pack.zip", "Album", listOf("a.jpg")),
            ),
        )
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.SmbFolder(1L, "Share/Album", listOf("a.jpg"))))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.WebDavFolder(2L, "Album", listOf("a.jpg"))))
    }

    @Test
    fun zipArchivesSupportPhotoGrid() {
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/pack.zip")))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/pack.cbz")))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.SmbStreamArchive(1L, "Share/pack.zip")))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.WebDavStreamArchive(2L, "pack.CBZ")))
    }

    @Test
    fun nonZipArchivesKeepDecodeSizeChrome() {
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.pdf")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.epub")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.rar")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.7z")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.SmbStreamArchive(1L, "Share/book.tar")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.WebDavStreamArchive(2L, "book.cbr")))
    }

    @Test
    fun smallGalleriesUseCappedSheet() {
        assertTrue(readerPhotoGridHalfScreen(0, capHeight = true))
        assertTrue(readerPhotoGridHalfScreen(1, capHeight = true))
        assertTrue(readerPhotoGridHalfScreen(READER_PHOTO_GRID_FULL_EXPAND_MIN - 1, capHeight = true))
        assertFalse(readerPhotoGridHalfScreen(READER_PHOTO_GRID_FULL_EXPAND_MIN, capHeight = true))
        assertFalse(readerPhotoGridHalfScreen(500, capHeight = true))
        assertFalse(readerPhotoGridHalfScreen(1, capHeight = false))
    }

    @Test
    fun localZipPageCoverUsesEncodedMemberPath() {
        val cover = readerPageCover(
            ReaderScreenArgs.LocalZipFolder("/sdcard/pack.zip", "Album", listOf("a.jpg")),
            "a.jpg",
        )
        assertEquals(ZipPaths.encodePath("/sdcard/pack.zip", "Album/a.jpg"), (cover as com.hippo.ehviewer.ui.main.BrowseCover.Local).path)
    }
}
