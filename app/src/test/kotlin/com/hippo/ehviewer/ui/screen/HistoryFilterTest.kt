package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.LOCAL_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.LOCAL_BROWSE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FILE_TOKEN
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.SMB_BROWSE_TOKEN
import com.hippo.ehviewer.library.SMB_FILE_TOKEN
import com.hippo.ehviewer.library.SMB_FOLDER_TOKEN
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_BROWSE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FILE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FOLDER_TOKEN
import com.hippo.ehviewer.ui.main.BrowseOverflowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterTest {
    @Test
    fun uploadResolvesHistoryFilesAndRemoteFolders() = runBlocking {
        for ((kind, tokens) in listOf(
            ExplorerWindows.Kind.Smb to listOf(SMB_BROWSE_TOKEN, SMB_FOLDER_TOKEN, SMB_ARCHIVE_TOKEN, SMB_FILE_TOKEN),
            ExplorerWindows.Kind.WebDav to listOf(WEBDAV_BROWSE_TOKEN, WEBDAV_FOLDER_TOKEN, WEBDAV_ARCHIVE_TOKEN, WEBDAV_FILE_TOKEN),
        )) {
            tokens.forEachIndexed { index, token ->
                val file = requireNotNull(historyUploadFile(item(token, "album", "7\u0000photos/album")))
                assertEquals(kind, file.source.kind)
                assertEquals(7L, file.source.sourceId)
                assertEquals("photos/album", file.source.path)
                assertEquals(index < 2, file.directory)
            }
        }
        for (token in listOf(LOCAL_ARCHIVE_TOKEN, LOCAL_FILE_TOKEN)) {
            val file = requireNotNull(historyUploadFile(item(token, "book.zip", "/books/book.zip")))
            assertEquals(ExplorerWindows.Kind.Local, file.source.kind)
            assertEquals("/books/book.zip", file.source.path)
            assertFalse(file.directory)
        }
    }

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
        assertTrue(filterHistoryFileItems(items, HistorySection.Media, ebookAsGallery = false).isEmpty())
        assertEquals(items, filterHistoryFileItems(items, HistorySection.Documents, ebookAsGallery = false))
        assertTrue(LocalHistory.isHistoryPdfOrMobi(pdf))
        assertTrue(LocalHistory.isHistoryPdfOrMobi(mobi))
    }

    @Test
    fun epubFollowsShowAsGalleryToggle() {
        val epub = item(LOCAL_ARCHIVE_TOKEN, "book.epub", "/books/book.epub")
        assertEquals(listOf(epub), filterHistoryFileItems(listOf(epub), HistorySection.Media))
        assertEquals(listOf(epub), filterHistoryFileItems(listOf(epub), HistorySection.Documents))
        assertTrue(
            filterHistoryFileItems(listOf(epub), HistorySection.Media, ebookAsGallery = false).isEmpty(),
        )
        assertEquals(
            listOf(epub),
            filterHistoryFileItems(listOf(epub), HistorySection.Documents, ebookAsGallery = false),
        )
        assertFalse(LocalHistory.isHistoryPdfOrMobi(epub))
        assertTrue(LocalHistory.isHistoryGalleryEbook(epub))
    }

    @Test
    fun looseTextStaysDocumentsWhenEbooksShowAsGallery() {
        val txt = item(LOCAL_FILE_TOKEN, "notes.txt", "/books/notes.txt")
        assertTrue(filterHistoryFileItems(listOf(txt), HistorySection.Media, ebookAsGallery = true).isEmpty())
        assertEquals(listOf(txt), filterHistoryFileItems(listOf(txt), HistorySection.Documents, ebookAsGallery = true))
    }

    @Test
    fun ebookHistoryKindFromName() {
        val txt = item(LOCAL_FILE_TOKEN, "notes.txt", "/books/notes.txt")
        val epub = item(LOCAL_ARCHIVE_TOKEN, "book.epub", "/books/book.epub")
        val html = item(LOCAL_FILE_TOKEN, "index.html", "/site/index.html")
        val zip = item(LOCAL_ARCHIVE_TOKEN, "pack.cbz", "/comics/pack.cbz")
        val pdf = item(LOCAL_ARCHIVE_TOKEN, "guide.pdf", "/docs/guide.pdf")
        val video = item(LOCAL_FILE_TOKEN, "clip.mp4", "/videos/clip.mp4")
        val dir = item(LOCAL_BROWSE_TOKEN, "Notes", "/root/Notes")
        assertTrue(LocalHistory.isHistoryEbook(txt))
        assertTrue(LocalHistory.isHistoryEbook(epub))
        assertTrue(LocalHistory.isHistoryEbook(html))
        assertFalse(LocalHistory.isHistoryEbook(zip))
        assertFalse(LocalHistory.isHistoryEbook(pdf))
        assertFalse(LocalHistory.isHistoryEbook(video))
        assertFalse(LocalHistory.isHistoryEbook(dir))
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

    @Test
    fun overflowKindMatchesFolderView() {
        val dir = item(LOCAL_BROWSE_TOKEN, "Album", "1\u0000photos")
        val folder = item(LOCAL_FOLDER_TOKEN, "Album", "1\u0000photos/Album")
        val zip = item(LOCAL_ARCHIVE_TOKEN, "pack.cbz", "/comics/pack.cbz")
        val pdf = item(LOCAL_ARCHIVE_TOKEN, "guide.pdf", "/docs/guide.pdf")
        val txt = item(LOCAL_FILE_TOKEN, "notes.txt", "/books/notes.txt")
        val html = item(LOCAL_FILE_TOKEN, "index.html", "/site/index.html")
        val video = item(LOCAL_FILE_TOKEN, "clip.mp4", "/videos/clip.mp4")
        assertEquals(BrowseOverflowKind.Common, historyOverflowKind(dir))
        assertEquals(BrowseOverflowKind.Gallery, historyOverflowKind(folder))
        assertEquals(BrowseOverflowKind.Gallery, historyOverflowKind(zip))
        assertEquals(BrowseOverflowKind.Pdf, historyOverflowKind(pdf))
        assertEquals(BrowseOverflowKind.Pdf, historyOverflowKind(txt))
        assertEquals(BrowseOverflowKind.Webpage, historyOverflowKind(html))
        assertEquals(BrowseOverflowKind.Video, historyOverflowKind(video))
        assertTrue(historyOverflowHasPhotoGrid(folder))
        assertFalse(historyOverflowHasPhotoGrid(zip))
        assertTrue(historyOverflowHasImageReader(pdf))
        assertFalse(historyOverflowHasImageReader(item(LOCAL_FILE_TOKEN, "guide.pdf", "/docs/guide.pdf")))
        assertTrue(historyOverflowHasFavorite(dir))
        assertFalse(historyOverflowHasFavorite(txt))
        assertTrue(historyGalleryConvertible(folder))
        assertTrue(historyGalleryConvertible(item(SMB_FOLDER_TOKEN, "Album", "7\u0000photos/Album")))
        assertTrue(historyGalleryConvertible(item(WEBDAV_FOLDER_TOKEN, "Album", "7\u0000photos/Album")))
        assertTrue(historyGalleryConvertible(item(LOCAL_GALLERY_TOKEN, "Album", "")))
        assertFalse(historyGalleryConvertible(item(LOCAL_FOLDER_TOKEN, "Album", "1\u0000photos/album.zip")))
        assertFalse(historyGalleryConvertible(item(LOCAL_FOLDER_TOKEN, "Album", "1\u0000photos/album.zip|Album")))
        assertFalse(historyGalleryConvertible(item(SMB_FOLDER_TOKEN, "Album", "7\u0000photos/album.cbz/inner")))
        assertFalse(historyGalleryConvertible(zip))
        assertFalse(historyGalleryConvertible(dir))
        assertFalse(historyGalleryConvertible(item(LOCAL_GALLERY_TOKEN, "pack.cbz", "/comics/pack.cbz", category = 1)))
    }

    @Test
    fun searchIgnoresMediaDocumentsFilter() {
        val txt = item(LOCAL_FILE_TOKEN, "notes.txt", "/books/notes.txt")
        val zip = item(LOCAL_ARCHIVE_TOKEN, "pack.cbz", "/comics/pack.cbz")
        val files = listOf(txt, zip)
        assertEquals(listOf(zip), filterHistoryFileItems(files, HistorySection.Media))
        assertEquals(listOf(txt), filterHistoryFileItems(files, HistorySection.Documents))
        assertEquals(files, filterHistoryFileItems(files, HistorySection.Media, allTypes = true))
        assertEquals(files, filterHistoryFileItems(files, HistorySection.Documents, allTypes = true))
    }

    @Test
    fun historyTabReusesTheRamListUntilTheViewChanges() {
        HistoryListMemory.resetForTest()
        val dir = item(LOCAL_BROWSE_TOKEN, "Notes", "/root/Notes")
        val zip = item(LOCAL_ARCHIVE_TOKEN, "pack.cbz", "/comics/pack.cbz")
        val txt = item(LOCAL_FILE_TOKEN, "notes.txt", "/books/notes.txt")
        val rows = listOf(dir, zip, txt)
        val key = HistoryListKey("", HistorySection.Media, ebookAsGallery = true)
        HistoryListMemory.store(rows)
        val first = HistoryListMemory.prepare(rows, key)
        assertEquals(listOf(dir), first.directories)
        assertEquals(listOf(zip), first.shownFiles)
        assertSame(first, HistoryListMemory.peek(rows, key))
        assertSame(first, HistoryListMemory.prepare(rows, key))
        val newer = listOf(dir, zip, txt, item(LOCAL_ARCHIVE_TOKEN, "more.cbz", "/comics/more.cbz"))
        assertSame(first, HistoryListMemory.display(newer, key))
        assertNull(HistoryListMemory.peek(newer, key))
        assertNull(HistoryListMemory.display(rows, key.copy(section = HistorySection.Documents)))
        assertNull(HistoryListMemory.display(emptyList(), key))
    }

    private fun item(token: String, title: String, uploader: String, category: Int = 0) = BaseGalleryInfo(
        gid = title.hashCode().toLong(),
        token = token,
        title = title,
        uploader = uploader,
        category = category,
    )
}
