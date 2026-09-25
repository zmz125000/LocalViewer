package com.hippo.ehviewer.library

import com.hippo.ehviewer.library.document.EbookChapter
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EbookBodyCacheTest {
    @After
    fun tearDown() {
        EbookBodyCache.rootOverride?.deleteRecursively()
        EbookBodyCache.rootOverride = null
    }

    @Test
    fun bodyCacheIsNotPdfTocOrImageIndex() {
        val root = createTempDirectory("ebook-body").toFile()
        EbookBodyCache.rootOverride = root
        val key = "/books/same.txt"
        val chapters = listOf(
            EbookChapter("Cover", "hello", 0),
            EbookChapter("Chapter", "world", 1),
        )
        EbookBodyCache.save(key, fileSize = 1000, chapters = chapters)
        assertEquals(chapters, EbookBodyCache.load(key, fileSize = 1000))
        assertNull(EbookBodyCache.load(key, fileSize = 1001))

        val file = EbookBodyCache.fileFor(key)
        assertEquals(root, file.parentFile)
        assertFalse(file.path.contains("document_extract"))
        assertFalse(file.path.contains("pdf_toc"))
        assertNotEquals(sha256(key), file.name.substringBefore('.'))
        assertEquals(sha256(EbookBodyCache.identity(key)), file.name.substringBefore('.'))
    }

    @Test
    fun emptyChaptersAreNotStored() {
        val root = createTempDirectory("ebook-body-empty").toFile()
        EbookBodyCache.rootOverride = root
        EbookBodyCache.save("/books/empty.txt", fileSize = 10, chapters = emptyList())
        assertNull(EbookBodyCache.load("/books/empty.txt", fileSize = 10))
    }

    private fun sha256(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
