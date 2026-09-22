package com.hippo.ehviewer.ui.reader

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.library.ZipPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPhotoGridTest {
    @Test
    fun folderGalleriesSupportPhotoGrid() {
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.LocalFolder("/sdcard/Album")))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.LocalImageList(page = 3)))
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
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.epub")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.rar")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.7z")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.SmbStreamArchive(1L, "Share/book.tar")))
        assertFalse(readerGallerySupportsPhotoGrid(ReaderScreenArgs.WebDavStreamArchive(2L, "book.cbr")))
    }

    @Test
    fun pdfArchivesSupportPhotoGrid() {
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.Archive("/sdcard/book.pdf")))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.SmbStreamArchive(1L, "Share/book.pdf")))
        assertTrue(readerGallerySupportsPhotoGrid(ReaderScreenArgs.WebDavStreamArchive(2L, "book.PDF")))
        assertEquals(
            "/sdcard/book.pdf",
            readerPdfCacheKey(ReaderScreenArgs.Archive("/sdcard/book.pdf")),
        )
        assertEquals(
            "smb:1:Share/book.pdf",
            readerPdfCacheKey(ReaderScreenArgs.SmbStreamArchive(1L, "Share/book.pdf")),
        )
        assertEquals(
            "webdav:2:book.PDF",
            readerPdfCacheKey(ReaderScreenArgs.WebDavStreamArchive(2L, "book.PDF")),
        )
        val cover = readerPageCover(
            ReaderScreenArgs.Archive("/sdcard/book.pdf"),
            "page.webp",
            index = 3,
        )
        val page = cover as com.hippo.ehviewer.ui.main.BrowseCover.DocumentPage
        assertEquals("/sdcard/book.pdf", page.cacheKey)
        assertEquals(3, page.index)
    }

    @Test
    fun phonePhotoGridSheetKeepsMaterialMaxWidth() {
        assertEquals(
            BottomSheetDefaults.SheetMaxWidth,
            readerPhotoGridSheetMaxWidth(smallestWidthDp = 411, screenWidthDp = 411),
        )
        assertEquals(
            BottomSheetDefaults.SheetMaxWidth,
            readerPhotoGridSheetMaxWidth(smallestWidthDp = 411, screenWidthDp = 891),
        )
    }

    @Test
    fun tabletPhotoGridSheetUsesMostOfScreenWidth() {
        val portrait = readerPhotoGridSheetMaxWidth(smallestWidthDp = 800, screenWidthDp = 800)
        assertEquals((800 * READER_PHOTO_GRID_TABLET_WIDTH_FRACTION).dp, portrait)
        assertTrue(portrait > BottomSheetDefaults.SheetMaxWidth)

        val landscape = readerPhotoGridSheetMaxWidth(smallestWidthDp = 800, screenWidthDp = 1280)
        assertEquals((1280 * READER_PHOTO_GRID_TABLET_WIDTH_FRACTION).dp, landscape)
        assertTrue(landscape > portrait)
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
    fun sheetHeightUsesScreenPixelsNotUnboundedFill() {
        assertEquals(560.dp, readerSheetHeightDp(screenHeightDp = 800, capHeight = true))
        assertEquals(800.dp, readerSheetHeightDp(screenHeightDp = 800, capHeight = false))
        assertEquals((411 * READER_SHEET_HEIGHT_FRACTION).dp, readerSheetHeightDp(411, capHeight = true))
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
