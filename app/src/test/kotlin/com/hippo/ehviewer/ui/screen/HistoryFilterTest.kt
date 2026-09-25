package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.library.LOCAL_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.LOCAL_BROWSE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FILE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterTest {
    @Test
    fun directoriesAreNeverDocuments() {
        val dir = item(LOCAL_BROWSE_TOKEN, "Notes", "/root/Notes")
        assertFalse(LocalHistory.isHistoryDocument(dir))
        assertTrue(LocalHistory.isBrowseDirectory(dir))
    }

    @Test
    fun txtGoesToDocumentsOnly() {
        val txt = item(LOCAL_FILE_TOKEN, "notes.txt", "/books/notes.txt")
        val media = filterHistoryFileItems(listOf(txt), HistorySection.Media)
        val docs = filterHistoryFileItems(listOf(txt), HistorySection.Documents)
        assertTrue(media.isEmpty())
        assertEquals(listOf(txt), docs)
    }

    @Test
    fun zipGalleryGoesToMediaOnly() {
        val zip = item(LOCAL_ARCHIVE_TOKEN, "pack.cbz", "/comics/pack.cbz")
        val media = filterHistoryFileItems(listOf(zip), HistorySection.Media)
        val docs = filterHistoryFileItems(listOf(zip), HistorySection.Documents)
        assertEquals(listOf(zip), media)
        assertTrue(docs.isEmpty())
    }

    @Test
    fun folderGalleryGoesToMediaOnly() {
        val folder = item(LOCAL_FOLDER_TOKEN, "Album", "1\u0000photos/Album")
        val media = filterHistoryFileItems(listOf(folder), HistorySection.Media)
        val docs = filterHistoryFileItems(listOf(folder), HistorySection.Documents)
        assertEquals(listOf(folder), media)
        assertTrue(docs.isEmpty())
    }

    @Test
    fun pdfAndMobiAppearInBoth() {
        val pdf = item(LOCAL_ARCHIVE_TOKEN, "guide.pdf", "/docs/guide.pdf")
        val mobi = item(LOCAL_FILE_TOKEN, "novel.mobi", "/books/novel.mobi")
        val items = listOf(pdf, mobi)
        assertEquals(items, filterHistoryFileItems(items, HistorySection.Media))
        assertEquals(items, filterHistoryFileItems(items, HistorySection.Documents))
        assertTrue(LocalHistory.isHistoryPdfOrMobi(pdf))
        assertTrue(LocalHistory.isHistoryPdfOrMobi(mobi))
    }

    @Test
    fun epubIsDocumentsOnly() {
        val epub = item(LOCAL_ARCHIVE_TOKEN, "book.epub", "/books/book.epub")
        assertTrue(filterHistoryFileItems(listOf(epub), HistorySection.Media).isEmpty())
        assertEquals(listOf(epub), filterHistoryFileItems(listOf(epub), HistorySection.Documents))
        assertFalse(LocalHistory.isHistoryPdfOrMobi(epub))
    }

    @Test
    fun videoGoesToMediaOnly() {
        val video = item(LOCAL_FILE_TOKEN, "clip.mp4", "/videos/clip.mp4")
        assertEquals(listOf(video), filterHistoryFileItems(listOf(video), HistorySection.Media))
        assertTrue(filterHistoryFileItems(listOf(video), HistorySection.Documents).isEmpty())
    }

    @Test
    fun documentDetectedFromPathWhenTitleHasNoExtension() {
        val pdf = item(LOCAL_FILE_TOKEN, "Guide", "/docs/guide.PDF")
        assertTrue(LocalHistory.isHistoryDocument(pdf))
        assertTrue(LocalHistory.isHistoryPdfOrMobi(pdf))
        assertEquals(listOf(pdf), filterHistoryFileItems(listOf(pdf), HistorySection.Media))
        assertEquals(listOf(pdf), filterHistoryFileItems(listOf(pdf), HistorySection.Documents))
    }

    @Test
    fun mixedListKeepsDirectoriesOutOfFileFilter() {
        val dir = item(LOCAL_BROWSE_TOKEN, "Root", "1\u0000")
        val pdf = item(LOCAL_FILE_TOKEN, "a.pdf", "/a.pdf")
        val txt = item(LOCAL_FILE_TOKEN, "b.txt", "/b.txt")
        val zip = item(LOCAL_ARCHIVE_TOKEN, "c.cbz", "/c.cbz")
        val files = listOf(pdf, txt, zip)
        assertEquals(listOf(pdf, zip), filterHistoryFileItems(files, HistorySection.Media))
        assertEquals(listOf(pdf, txt), filterHistoryFileItems(files, HistorySection.Documents))
        assertFalse(LocalHistory.matchesHistorySection(dir, documents = true))
        assertTrue(LocalHistory.matchesHistorySection(dir, documents = false))
    }

    private fun item(token: String, title: String, uploader: String) = BaseGalleryInfo(
        gid = title.hashCode().toLong(),
        token = token,
        title = title,
        uploader = uploader,
    )
}
