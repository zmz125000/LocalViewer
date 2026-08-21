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
                "Pictures/Comics" to "cover.jpg",
                "Pictures/Comics/Series1" to "02.png",
                "Pictures/Comics/Series1" to "01.png",
                "Pictures/Comics/S/leaf" to "a.webp",
                "Pictures/Other" to "skip.jpg",
                "Pictures/Comics/Series1" to "note.txt",
            ),
        )
        assertEquals(listOf("cover.jpg"), folders[""])
        assertEquals(listOf("01.png", "02.png"), folders["Series1"])
        assertEquals(listOf("a.webp"), folders["S/leaf"])
        assertEquals(setOf("", "Series1", "S/leaf"), folders.keys)
    }

    @Test
    fun `relativeUnderRoot rejects paths outside the source`() {
        assertEquals("", SafMediaStoreListing.relativeUnderRoot("Pictures", "Pictures"))
        assertEquals("Comics", SafMediaStoreListing.relativeUnderRoot("Pictures", "Pictures/Comics"))
        assertNull(SafMediaStoreListing.relativeUnderRoot("Pictures", "Download"))
        assertEquals("a/b", SafMediaStoreListing.relativeUnderRoot("", "a/b"))
    }

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
