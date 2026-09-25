package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesTest {
    @Test
    fun knownNonVideoNonPdfTypesAreSpecific() {
        assertEquals("audio/mpeg", mimeTypeForFileName("track.mp3"))
        assertEquals("audio/flac", mimeTypeForFileName("album.FLAC"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            mimeTypeForFileName("notes.docx"),
        )
        assertEquals("application/vnd.android.package-archive", mimeTypeForFileName("app.apk"))
        assertEquals("text/plain", mimeTypeForFileName("readme.txt"))
        assertEquals("application/zip", mimeTypeForFileName("pack.zip"))
        assertEquals("image/jpeg", mimeTypeForFileName("shot.jpg"))
        assertEquals("image/heif", mimeTypeForFileName("clip.hif"))
    }

    @Test
    fun videoAndPdfStayOnTheirTypes() {
        assertEquals("video/mp4", mimeTypeForFileName("clip.mp4"))
        assertEquals("application/pdf", mimeTypeForFileName("doc.pdf"))
        assertTrue(mimeTypeForFileName("clip.mp4").startsWith("video/"))
    }

    @Test
    fun browseDocumentsCoverOfficeTextAndEbooks() {
        assertTrue(isBrowseDocumentFileName("guide.pdf"))
        assertTrue(isBrowseDocumentFileName("book.EPUB"))
        assertTrue(isBrowseDocumentFileName("notes.docx"))
        assertTrue(isBrowseDocumentFileName("sheet.xlsx"))
        assertTrue(isBrowseDocumentFileName("deck.pptx"))
        assertTrue(isBrowseDocumentFileName("paper.odt"))
        assertTrue(isBrowseDocumentFileName("readme.txt"))
        assertTrue(isBrowseDocumentFileName("index.html"))
        assertTrue(isBrowseDocumentFileName("novel.mobi"))
        assertTrue(isMobiFileName("novel.mobi"))
        assertTrue(isMobiFileName("novel.MOBI"))
        assertFalse(isMobiFileName("novel.azw"))
        assertFalse(isMobiFileName("guide.pdf"))
        assertFalse(isBrowseDocumentFileName("pack.zip"))
        assertFalse(isBrowseDocumentFileName("pack.cbz"))
        assertFalse(isBrowseDocumentFileName("clip.mp4"))
        assertFalse(isBrowseDocumentFileName("shot.jpg"))
        assertFalse(isBrowseDocumentFileName(".secret.txt"))
        assertTrue(isDocumentFileName("guide.pdf"))
        assertFalse(isDocumentFileName("notes.docx"))
    }

    @Test
    fun ebookReaderTypesAreEpubTextHtmlFb2() {
        assertTrue(isEbookFileName("novel.epub"))
        assertTrue(isEbookFileName("notes.TXT"))
        assertTrue(isEbookFileName("page.html"))
        assertTrue(isEbookFileName("book.fb2"))
        assertTrue(isEbookFileName("readme.md"))
        assertTrue(isPdfOrEbookFileName("guide.pdf"))
        assertTrue(isPdfOrEbookFileName("novel.epub"))
        assertFalse(isEbookFileName("guide.pdf"))
        assertFalse(isEbookFileName("pack.zip"))
        assertFalse(isEbookFileName("shot.jpg"))
        assertFalse(isEbookFileName(".hidden.txt"))
    }

    @Test
    fun zipAsDirExtensionsAreZipAndCbzOnly() {
        assertTrue(isZipArchiveFileName("album.zip"))
        assertTrue(isZipArchiveFileName("album.CBZ"))
        assertFalse(isZipArchiveFileName("album.rar"))
        assertFalse(isZipArchiveFileName("album.cbr"))
        assertFalse(isZipArchiveFileName("album.7z"))
        assertFalse(isZipArchiveFileName("album.tar"))
        assertFalse(isZipArchiveFileName("album.cbt"))
        assertFalse(isZipArchiveFileName("album.pdf"))
        assertFalse(isZipArchiveFileName("album.epub"))
    }

    @Test
    fun openCacheConfirmIsOver100MiB() {
        assertFalse(needsOpenCacheConfirm(null))
        assertFalse(needsOpenCacheConfirm(OPEN_CACHE_WARN_BYTES))
        assertFalse(needsOpenCacheConfirm(OPEN_CACHE_WARN_BYTES - 1))
        assertTrue(needsOpenCacheConfirm(OPEN_CACHE_WARN_BYTES + 1))
        assertEquals(100L * 1024L * 1024L, OPEN_CACHE_WARN_BYTES)
    }

    @Test
    fun zipMemberCoverExtractIsImageAndVideoOnly() {
        assertTrue(isZipMemberCoverExtractAllowed("Album/a.jpg"))
        assertTrue(isZipMemberCoverExtractAllowed("clip.MP4"))
        assertFalse(isZipMemberCoverExtractAllowed("notes.pdf"))
        assertFalse(isZipMemberCoverExtractAllowed("nested.zip"))
        assertFalse(isZipMemberCoverExtractAllowed("readme.txt"))
        assertFalse(isZipMemberCoverExtractAllowed("doc.epub"))
        assertFalse(isZipMemberCoverExtractAllowed("noext"))
    }

    @Test
    fun unknownUsesGenericViewMime() {
        assertEquals(GENERIC_FILE_MIME, mimeTypeForFileName("noext"))
        assertEquals(GENERIC_FILE_MIME, mimeTypeForFileName("weird.unknownfmt"))
        assertFalse(mimeTypeForFileName("weird.unknownfmt") == "application/octet-stream")
    }
}
