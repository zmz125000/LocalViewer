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
