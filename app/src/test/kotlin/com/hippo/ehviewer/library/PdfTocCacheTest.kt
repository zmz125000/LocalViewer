package com.hippo.ehviewer.library

import com.hippo.ehviewer.library.document.PdfTocEntry
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTocCacheTest {
    @After
    fun tearDown() {
        PdfTocCache.rootOverride?.deleteRecursively()
        PdfTocCache.rootOverride = null
    }

    @Test
    fun tocCacheIsNotTheImageIndex() {
        val root = createTempDirectory("pdf-toc").toFile()
        PdfTocCache.rootOverride = root
        val key = "/books/same.pdf"
        val chapters = listOf(
            PdfTocEntry("Cover", 0, 0),
            PdfTocEntry("Chapter", 12, 1),
        )
        PdfTocCache.save(key, fileSize = 1000, pageCount = 40, chapters = chapters)
        assertEquals(chapters, PdfTocCache.load(key, fileSize = 1000, pageCount = 40))
        assertNull(PdfTocCache.load(key, fileSize = 1001, pageCount = 40))
        assertNull(PdfTocCache.load(key, fileSize = 1000, pageCount = 41))

        val file = PdfTocCache.fileFor(key)
        assertEquals(root, file.parentFile)
        assertFalse(file.path.contains("document_extract"))
        assertNotEquals(sha256(key), file.name.substringBefore('.'))
        assertEquals(sha256(PdfTocCache.identity(key)), file.name.substringBefore('.'))

        PdfTocCache.save(key, fileSize = 1000, pageCount = 40, chapters = emptyList())
        assertEquals(emptyList<PdfTocEntry>(), PdfTocCache.load(key, 1000, 40))
    }

    private fun sha256(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
