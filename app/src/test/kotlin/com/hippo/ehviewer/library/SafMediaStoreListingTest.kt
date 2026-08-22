package com.hippo.ehviewer.library

import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafMediaStoreListingTest {
    @Test
    fun `merge keeps MediaStore files and drops SAF duplicates`() {
        val ms = listOf(
            child("01.jpg", isDir = false, fromMediaStore = true),
            child("album", isDir = true, fromMediaStore = true),
        )
        val saf = listOf(
            child("01.jpg", isDir = false),
            child("album", isDir = true),
            child("book.cbz", isDir = false),
            child("only-archives", isDir = true),
        )
        val merged = SafMediaStoreListing.merge(ms, saf)
        assertEquals(
            listOf("01.jpg", "album", "book.cbz", "only-archives"),
            merged.map { it.name },
        )
        assertTrue(merged[0].fromMediaStore)
        assertTrue(!merged.first { it.name == "book.cbz" }.fromMediaStore)
    }

    @Test
    fun `image folders group nested MediaStore rows under the SAF root`() {
        val folders = SafMediaStoreListing.imageFoldersUnderRoot(
            rootRelativeDir = "Pictures/Comics",
            files = listOf(
                img("Pictures/Comics", "cover.jpg", 1000L),
                img("Pictures/Comics/Series1", "02.png", 3000L),
                img("Pictures/Comics/Series1", "01.png", 2000L),
                img("Pictures/Comics/S/leaf", "a.webp", 500L),
                img("Pictures/Other", "skip.jpg", 9000L),
                img("Pictures/Comics/Series1", "note.txt", 8000L),
            ),
        )
        assertEquals(listOf("cover.jpg"), folders[""]?.names)
        assertEquals(1000L, folders[""]?.latestImageMs)
        assertEquals(listOf("01.png", "02.png"), folders["Series1"]?.names)
        assertEquals(3000L, folders["Series1"]?.latestImageMs)
        assertEquals(listOf("a.webp"), folders["S/leaf"]?.names)
        assertEquals(500L, folders["S/leaf"]?.latestImageMs)
        assertEquals(setOf("", "Series1", "S/leaf"), folders.keys)
    }

    @Test
    fun `relativeUnderRoot rejects paths outside the source`() {
        assertEquals("", SafMediaStoreListing.relativeUnderRoot("Pictures", "Pictures"))
        assertEquals("Comics", SafMediaStoreListing.relativeUnderRoot("Pictures", "Pictures/Comics"))
        assertNull(SafMediaStoreListing.relativeUnderRoot("Pictures", "Download"))
        assertEquals("a/b", SafMediaStoreListing.relativeUnderRoot("", "a/b"))
    }

    private fun img(parent: String, name: String, lastModifiedMs: Long = 0L) = SafMediaStoreListing.ImageFile(parent, name, lastModifiedMs)

    private fun child(
        name: String,
        isDir: Boolean,
        fromMediaStore: Boolean = false,
    ) = BrowseChild(
        name = name,
        isDirectory = isDir,
        path = "/t/$name".toPath(),
        fromMediaStore = fromMediaStore,
    )
}
