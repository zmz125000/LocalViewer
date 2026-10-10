package com.hippo.ehviewer.library

import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EbookSiblingPlaylistTest {
    @After
    fun tearDown() {
        ReaderGalleryPlaylist.clear()
    }

    @Test
    fun localTxtRegularFilesArePlaylistSiblings() {
        val a = "/tmp/books/a.txt".toPath()
        val b = "/tmp/books/b.txt".toPath()
        val zip = "/tmp/books/c.cbz".toPath()
        ReaderGalleryPlaylist.setFromLocalBrowse(
            rootId = 1L,
            parentPath = "/tmp/books",
            parentRelative = "books",
            entries = listOf(
                BrowseEntry.RegularFile(name = "a.txt", path = a),
                BrowseEntry.RegularFile(name = "notes.docx", path = "/tmp/books/notes.docx".toPath()),
                BrowseEntry.RegularFile(name = "b.txt", path = b),
                BrowseEntry.ArchiveGallery(name = "c.cbz", path = zip),
            ),
        )
        val next = ReaderGalleryPlaylist.sibling(ReaderScreenArgs.Archive(a.toString()), next = true)
        assertEquals(ReaderScreenArgs.Archive(b.toString(), page = -1, info = null), next)
        val skipDocx = ReaderGalleryPlaylist.sibling(ReaderScreenArgs.Archive(b.toString()), next = true)
        assertEquals(ReaderScreenArgs.Archive(zip.toString(), page = -1, info = null), skipDocx)
        assertNull(ReaderGalleryPlaylist.sibling(ReaderScreenArgs.Archive(zip.toString()), next = true))
    }

    @Test
    fun networkEbooksHopWhetherGalleryOrFile() {
        ReaderGalleryPlaylist.setFromSmbBrowse(
            sourceId = 4L,
            parentRelative = "books",
            entries = listOf(
                BrowseEntryRemote.ArchiveGallery(name = "guide.pdf", fileName = "guide.pdf"),
                BrowseEntryRemote.ArchiveGallery(name = "novel.azw3", fileName = "novel.azw3"),
                BrowseEntryRemote.RegularFile(name = "notes.txt", fileName = "notes.txt"),
                BrowseEntryRemote.ArchiveGallery(name = "story.fb2", fileName = "story.fb2"),
                BrowseEntryRemote.RegularFile(name = "page.html", fileName = "page.html"),
                BrowseEntryRemote.ArchiveGallery(name = "book.epub", fileName = "book.epub"),
                BrowseEntryRemote.RegularFile(name = "readme.md", fileName = "readme.md"),
                BrowseEntryRemote.ArchiveGallery(name = "old.mobi", fileName = "old.mobi"),
                BrowseEntryRemote.RegularFile(name = "memo.docx", fileName = "memo.docx"),
            ),
        )
        val order = listOf(
            "books/guide.pdf",
            "books/novel.azw3",
            "books/notes.txt",
            "books/story.fb2",
            "books/page.html",
            "books/book.epub",
            "books/readme.md",
            "books/old.mobi",
        )
        for (i in 0 until order.lastIndex) {
            val next = ReaderGalleryPlaylist.sibling(
                ReaderScreenArgs.SmbStreamArchive(4L, order[i]),
                next = true,
            )
            assertEquals(order[i + 1], (next as ReaderScreenArgs.SmbStreamArchive).remotePath)
        }
        assertNull(
            ReaderGalleryPlaylist.sibling(
                ReaderScreenArgs.SmbStreamArchive(4L, order.last()),
                next = true,
            ),
        )
    }
}
