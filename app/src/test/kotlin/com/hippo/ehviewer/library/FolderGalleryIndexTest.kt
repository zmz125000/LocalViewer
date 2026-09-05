package com.hippo.ehviewer.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderGalleryIndexTest {
    @Test
    fun `self listing with empty relativeName is complete`() {
        val names = listOf("01.jpg", "02.jpg")
        val listing = listOf(gallery(relativeName = "", names = names))
        assertEquals(
            names,
            FolderGalleryIndex.namesFromListing("share/gal", listing, "share/gal"),
        )
    }

    @Test
    fun `parent listing matches child gallery`() {
        val names = listOf("a.png", "b.png")
        val listing = listOf(gallery(relativeName = "gal", names = names))
        assertEquals(
            names,
            FolderGalleryIndex.namesFromListing("share", listing, "share/gal"),
        )
    }

    @Test
    fun `promoted leaf is resolved from grandparent listing`() {
        val names = listOf("leaf-1.jpg")
        val listings = mapOf(
            "share" to listOf(gallery(relativeName = "S/leaf", names = names)),
        )
        val found = runBlocking {
            FolderGalleryIndex.namesWalkingParents("share/S/leaf") { listings[it] }
        }
        assertEquals(names, found)
    }

    @Test
    fun `capped or empty index is ignored so live list can run`() {
        val capped = gallery(relativeName = "gal", names = listOf("01.jpg"), capped = true)
        val empty = gallery(relativeName = "gal", names = emptyList())
        assertNull(FolderGalleryIndex.namesFromListing("share", listOf(capped), "share/gal"))
        assertNull(FolderGalleryIndex.namesFromListing("share", listOf(empty), "share/gal"))
    }

    @Test
    fun `walks to parent when self listing has no gallery row`() {
        val names = listOf("page.webp")
        val listings = mapOf(
            "share/gal" to listOf(BrowseEntryRemote.RegularFile("note.txt")),
            "share" to listOf(gallery(relativeName = "gal", names = names)),
        )
        val found = runBlocking {
            FolderGalleryIndex.namesWalkingParents("share/gal") { listings[it] }
        }
        assertEquals(names, found)
    }

    @Test
    fun `self listing image files are a complete index`() {
        val listing = listOf(
            BrowseEntryRemote.RegularFile("02.png"),
            BrowseEntryRemote.RegularFile("01.png"),
            BrowseEntryRemote.RegularFile("book.cbz"),
        )
        assertEquals(
            listOf("01.png", "02.png"),
            FolderGalleryIndex.namesFromListing("share/gal", listing, "share/gal"),
        )
    }

    @Test
    fun `sibling listing walks to grandparent for promoted leaf`() {
        val listing = listOf(gallery(relativeName = "S/leaf", names = listOf("a.jpg")))
        val found = runBlocking {
            FolderGalleryIndex.siblingListingWalkingParents("share/S/leaf") { dir ->
                if (dir == "share") listing else null
            }
        }
        assertEquals("share", found?.first)
        assertEquals(listing, found?.second)
    }

    @Test
    fun `zip wrapper gallery is resolved from parent listing`() {
        val names = listOf("a.jpg", "b.jpg")
        val listing = listOf(gallery(relativeName = "pack.zip/Album", names = names))
        assertEquals(
            names,
            FolderGalleryIndex.namesFromListing("share", listing, "share/pack.zip/Album"),
        )
    }

    @Test
    fun `zip interior listing matches child gallery`() {
        val names = listOf("a.jpg")
        val listings = mapOf(
            "share/pack.zip" to listOf(gallery(relativeName = "Album", names = names)),
        )
        val found = runBlocking {
            FolderGalleryIndex.namesWalkingParents("share/pack.zip/Album") { listings[it] }
        }
        assertEquals(names, found)
    }

    @Test
    fun `root gallery matches empty listed dir`() {
        val names = listOf("cover.jpg")
        val listing = listOf(gallery(relativeName = "gal", names = names))
        assertEquals(names, FolderGalleryIndex.namesFromListing("", listing, "gal"))
    }

    @Test
    fun `complete names skip capped and empty so photo grid can live-list`() {
        val names = listOf("b.jpg", "a.jpg")
        val row = gallery(relativeName = "gal", names = names)
        assertEquals(names, FolderGalleryIndex.completeNames(row))
        assertNull(FolderGalleryIndex.completeNames(gallery(relativeName = "gal", names = names, capped = true)))
        assertNull(FolderGalleryIndex.completeNames(gallery(relativeName = "gal", names = emptyList())))
        assertEquals(
            names,
            FolderGalleryIndex.photoGridRemoteFiles(names).map { it.fileName },
        )
        val local = FolderGalleryIndex.photoGridLocalFiles("/tmp/gal", zipInnerRel = null, names)
        assertEquals(names, local.map { it.name })
        assertEquals("/tmp/gal/b.jpg", local.first().path.toString())
        val zip = FolderGalleryIndex.photoGridLocalFiles("/tmp/pack.zip", zipInnerRel = "Album", names)
        assertEquals("zipfile:/tmp/pack.zip!Album/b.jpg", zip.first().path.toString())
    }

    private fun gallery(
        relativeName: String,
        names: List<String>,
        capped: Boolean = false,
    ) = BrowseEntryRemote.FolderGallery(
        name = "Gal",
        relativeName = relativeName,
        pageCount = names.size,
        pageCountCapped = capped,
        coverFileName = names.firstOrNull(),
        imageFileNames = names,
    )
}
