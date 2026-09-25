package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseContentModeSearchTest {
    private val listing = listOf(
        BrowseEntryRemote.FolderGallery(
            name = "Album",
            relativeName = "Album",
            pageCount = 2,
            coverFileName = "a.jpg",
            imageFileNames = listOf("a.jpg", "b.jpg"),
        ),
        BrowseEntryRemote.VideoFile(name = "clip.mp4", fileName = "clip.mp4"),
        BrowseEntryRemote.RegularFile(name = "notes.txt", fileName = "notes.txt"),
        BrowseEntryRemote.RegularFile(name = ".secret.txt", fileName = ".secret.txt", hidden = true),
        BrowseEntryRemote.ArchiveGallery(name = "guide.pdf", fileName = "guide.pdf"),
        BrowseEntryRemote.ArchiveGallery(name = "pack.cbz", fileName = "pack.cbz"),
    )

    @Test
    fun mediaModeHidesRegularFilesUntilLiveSearch() {
        val media = listing.filterRemoteByContentMode(BrowseContentMode.Media)
        assertFalse(media.any { it is BrowseEntryRemote.RegularFile })
        assertTrue(media.any { it is BrowseEntryRemote.VideoFile })
        assertTrue(media.any { it is BrowseEntryRemote.FolderGallery })

        val searching = listing.filterRemoteByContentMode(
            BrowseContentMode.Media,
            allTypes = true,
        )
        assertTrue(searching.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })
        val sections = searching.toRemoteBrowseSections()
        assertEquals(
            listOf("notes.txt"),
            sections.documents.filterIsInstance<BrowseEntryRemote.RegularFile>().map { it.name },
        )
        assertEquals(listOf(".secret.txt"), sections.files.map { it.name })
    }

    @Test
    fun photoModeLiveSearchKeepsFilesAndVideos() {
        val photo = listing.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertFalse(photo.any { it is BrowseEntryRemote.RegularFile })
        assertFalse(photo.any { it is BrowseEntryRemote.VideoFile })

        val searching = listing.filterRemoteByContentMode(
            BrowseContentMode.Galleries,
            allTypes = true,
        )
        assertTrue(searching.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })
        assertTrue(searching.any { it is BrowseEntryRemote.VideoFile && it.name == "clip.mp4" })
        assertTrue(searching.any { it is BrowseEntryRemote.FolderGallery })
    }

    @Test
    fun folderModeLiveSearchKeepsFolderGalleries() {
        val folder = listing.filterRemoteByContentMode(BrowseContentMode.Folder)
        assertFalse(folder.any { it is BrowseEntryRemote.FolderGallery })
        assertTrue(folder.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })

        val searching = listing.filterRemoteByContentMode(
            BrowseContentMode.Folder,
            allTypes = true,
        )
        assertTrue(searching.any { it is BrowseEntryRemote.FolderGallery && it.name == "Album" })
        assertTrue(searching.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })
    }

    @Test
    fun documentModeKeepsPdfAndTextNotZipOrMedia() {
        val docs = listing.filterRemoteByContentMode(BrowseContentMode.Document)
        assertTrue(docs.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "guide.pdf" })
        assertTrue(docs.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })
        assertFalse(docs.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "pack.cbz" })
        assertFalse(docs.any { it is BrowseEntryRemote.FolderGallery })
        assertFalse(docs.any { it is BrowseEntryRemote.VideoFile })
        val sections = docs.toRemoteBrowseSections()
        assertTrue(sections.documents.any { it.name == "guide.pdf" })
        assertTrue(sections.documents.any { it.name == "notes.txt" })
        assertTrue(sections.galleries.isEmpty())
        assertEquals(listOf(".secret.txt"), sections.files.map { it.name })
    }

    @Test
    fun liveSearchStillHidesHiddenWhenDisabled() {
        val searching = listing.filterRemoteByContentMode(
            BrowseContentMode.Media,
            showHiddenFiles = false,
            allTypes = true,
        )
        assertTrue(searching.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })
        assertFalse(searching.any { it.name == ".secret.txt" })
    }
}
