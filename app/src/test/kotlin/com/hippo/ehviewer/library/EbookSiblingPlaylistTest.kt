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
}
