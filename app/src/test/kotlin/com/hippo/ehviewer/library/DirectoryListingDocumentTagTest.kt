package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingDocumentTagTest {
    @Test
    fun directPdfShowsInDocumentMode() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Docs",
            entries = listOf(
                RemoteChild(name = "guide.pdf", isDirectory = false),
                RemoteChild(name = "pack.cbz", isDirectory = false),
                RemoteChild(name = "clip.mp4", isDirectory = false),
            ),
            childPeeks = emptyMap(),
        )
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("guide.pdf"), docs.map { it.name })
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertTrue(photo.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "guide.pdf" })
        assertTrue(photo.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "pack.cbz" })
    }

    @Test
    fun nestedPdfTagsParentDirAndIsNotPromoted() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Manuals", isDirectory = true)),
            childPeeks = mapOf(
                "Manuals" to listOf(RemoteChild(name = "Inner", isDirectory = true)),
            ),
            grandPeeks = mapOf(
                "Manuals/Inner" to listOf(RemoteChild(name = "guide.pdf", isDirectory = false)),
            ),
        )
        val manuals = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Manuals" }
        assertTrue(manuals.hasDocument)
        assertTrue(manuals.hasGallery)
        assertFalse(
            entries.any {
                it is BrowseEntryRemote.ArchiveGallery && it.name.contains("guide.pdf")
            },
        )
        assertFalse(entries.any { it.virtual && it.name.contains("guide", ignoreCase = true) })
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("Manuals"), docs.map { it.name })
        assertFalse(docs.any { it is BrowseEntryRemote.ArchiveGallery })
    }

    @Test
    fun nestedSingleVideoStillPromotesButPdfDoesNot() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Mix", isDirectory = true)),
            childPeeks = mapOf(
                "Mix" to listOf(
                    RemoteChild(name = "Clips", isDirectory = true),
                    RemoteChild(name = "Papers", isDirectory = true),
                ),
            ),
            grandPeeks = mapOf(
                "Mix/Clips" to listOf(RemoteChild(name = "a.mp4", isDirectory = false)),
                "Mix/Papers" to listOf(RemoteChild(name = "a.pdf", isDirectory = false)),
            ),
        )
        assertTrue(
            entries.any { it is BrowseEntryRemote.VideoFile && it.virtual },
        )
        assertFalse(
            entries.any { it is BrowseEntryRemote.ArchiveGallery && it.name.contains("pdf") },
        )
        val mix = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Mix" }
        assertTrue(mix.hasDocument)
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertTrue(docs.any { it is BrowseEntryRemote.Directory && it.name == "Mix" })
        assertFalse(docs.any { it is BrowseEntryRemote.VideoFile })
        assertFalse(docs.any { it is BrowseEntryRemote.ArchiveGallery })
    }

    @Test
    fun pdfInChildFolderIsNavigableNotLeafFileOnParent() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Papers", isDirectory = true)),
            childPeeks = mapOf(
                "Papers" to listOf(RemoteChild(name = "guide.pdf", isDirectory = false)),
            ),
        )
        val papers = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Papers" }
        assertEquals(DirPresence.Navigable, papers.presence)
        assertTrue(papers.hasDocument)
        assertFalse(entries.any { it is BrowseEntryRemote.ArchiveGallery })
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("Papers"), docs.map { it.name })
    }

    @Test
    fun officeFileShowsInDocumentModeAndIsNotPromoted() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Work",
            entries = listOf(
                RemoteChild(name = "memo.docx", isDirectory = false),
                RemoteChild(name = "sheet.xlsx", isDirectory = false),
                RemoteChild(name = "pack.zip", isDirectory = false),
            ),
            childPeeks = emptyMap(),
        )
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("memo.docx", "sheet.xlsx"), docs.map { it.name }.sorted())
        assertTrue(docs.all { it is BrowseEntryRemote.RegularFile })
        assertFalse(docs.any { it.name == "pack.zip" })

        val nested = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Office", isDirectory = true)),
            childPeeks = mapOf(
                "Office" to listOf(RemoteChild(name = "memo.docx", isDirectory = false)),
            ),
        )
        val office = nested.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Office" }
        assertTrue(office.hasDocument)
        assertFalse(office.hasGallery)
        assertFalse(nested.any { it is BrowseEntryRemote.RegularFile && it.name.contains("docx") })
        val nestedDocs = nested.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("Office"), nestedDocs.map { it.name })
    }

    @Test
    fun epubCountsAsDocumentZipDoesNot() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(
                RemoteChild(name = "Books", isDirectory = true),
                RemoteChild(name = "Zips", isDirectory = true),
            ),
            childPeeks = mapOf(
                "Books" to listOf(RemoteChild(name = "novel.epub", isDirectory = false)),
                "Zips" to listOf(RemoteChild(name = "vol1.zip", isDirectory = false)),
            ),
        )
        val books = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Books" }
        val zips = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Zips" }
        assertTrue(books.hasDocument)
        assertFalse(zips.hasDocument)
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("Books"), docs.map { it.name })
    }
}
