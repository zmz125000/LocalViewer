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
    fun unknownUsesGenericViewMime() {
        assertEquals(GENERIC_FILE_MIME, mimeTypeForFileName("noext"))
        assertEquals(GENERIC_FILE_MIME, mimeTypeForFileName("weird.unknownfmt"))
        assertFalse(mimeTypeForFileName("weird.unknownfmt") == "application/octet-stream")
    }
}
