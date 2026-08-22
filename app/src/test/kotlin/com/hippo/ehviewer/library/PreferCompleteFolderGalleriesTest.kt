package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferCompleteFolderGalleriesTest {
    @Test
    fun `empty new gallery keeps previous complete page list`() {
        val previous = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Gal",
                relativeName = "Gal",
                pageCount = 2,
                pageCountCapped = false,
                coverFileName = "a.jpg",
                imageFileNames = listOf("a.jpg", "b.jpg"),
            ),
        )
        val next = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Gal",
                relativeName = "Gal",
                pageCount = 0,
                pageCountCapped = false,
                coverFileName = null,
                imageFileNames = emptyList(),
            ),
        )
        val merged = preferCompleteFolderGalleries(previous, next)
        val gal = merged.filterIsInstance<BrowseEntryRemote.FolderGallery>().single()
        assertEquals(listOf("a.jpg", "b.jpg"), gal.imageFileNames)
        assertEquals(2, gal.pageCount)
        assertFalse(gal.pageCountCapped)
        assertEquals("a.jpg", gal.coverFileName)
    }

    @Test
    fun `capped new gallery keeps previous complete page list`() {
        val previous = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Gal",
                relativeName = "share/Gal",
                pageCount = 3,
                coverFileName = "1.png",
                imageFileNames = listOf("1.png", "2.png", "3.png"),
            ),
        )
        val next = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Gal",
                relativeName = "share\\Gal",
                pageCount = 99,
                pageCountCapped = true,
                coverFileName = "1.png",
                imageFileNames = listOf("1.png"),
            ),
        )
        val gal = preferCompleteFolderGalleries(previous, next)
            .filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .single()
        assertEquals(listOf("1.png", "2.png", "3.png"), gal.imageFileNames)
        assertEquals(3, gal.pageCount)
        assertFalse(gal.pageCountCapped)
    }

    @Test
    fun `complete new gallery is kept`() {
        val previous = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Gal",
                relativeName = "Gal",
                pageCount = 1,
                coverFileName = "old.jpg",
                imageFileNames = listOf("old.jpg"),
            ),
        )
        val next = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Gal",
                relativeName = "Gal",
                pageCount = 2,
                coverFileName = "a.jpg",
                imageFileNames = listOf("a.jpg", "b.jpg"),
            ),
        )
        val gal = preferCompleteFolderGalleries(previous, next)
            .filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .single()
        assertEquals(listOf("a.jpg", "b.jpg"), gal.imageFileNames)
        assertEquals(2, gal.pageCount)
    }

    @Test
    fun `directories and other kinds pass through`() {
        val previous = listOf(
            BrowseEntryRemote.Directory(
                name = "D",
                hasVideo = false,
                hasGallery = true,
                presence = DirPresence.Navigable,
            ),
        )
        val next = listOf(
            BrowseEntryRemote.Directory(
                name = "D",
                hasVideo = true,
                hasGallery = true,
                presence = DirPresence.Navigable,
            ),
            BrowseEntryRemote.RegularFile("x.txt"),
        )
        val merged = preferCompleteFolderGalleries(previous, next)
        assertEquals(2, merged.size)
        assertTrue(merged[0] is BrowseEntryRemote.Directory)
        assertTrue((merged[0] as BrowseEntryRemote.Directory).hasVideo)
    }
}
