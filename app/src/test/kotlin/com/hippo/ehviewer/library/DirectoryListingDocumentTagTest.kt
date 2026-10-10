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
        val photoSections = photo.toRemoteBrowseSections(BrowseContentMode.Galleries)
        assertTrue(photoSections.galleries.any { it.name == "guide.pdf" })
        assertTrue(photoSections.galleries.any { it.name == "pack.cbz" })
        assertTrue(photoSections.documents.isEmpty())
        val docSections = docs.toRemoteBrowseSections(BrowseContentMode.Document)
        assertEquals(listOf("guide.pdf"), docSections.documents.map { it.name })
        assertTrue(docSections.galleries.isEmpty())
    }

    @Test
    fun folderThumbFallsBackToArchiveOrPdf() {
        val direct = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Books", isDirectory = true)),
            childPeeks = mapOf(
                "Books" to listOf(
                    RemoteChild(name = "b.cbz", isDirectory = false),
                    RemoteChild(name = "a.pdf", isDirectory = false),
                ),
            ),
        )
        val books = direct.filterIsInstance<BrowseEntryRemote.Directory>().single()
        assertEquals("a.pdf", books.coverFileName)

        val imageWins = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Books", isDirectory = true)),
            childPeeks = mapOf(
                "Books" to listOf(
                    RemoteChild(name = "a.pdf", isDirectory = false),
                    RemoteChild(name = "c.jpg", isDirectory = false),
                ),
            ),
        )
        assertEquals(
            "c.jpg",
            imageWins.filterIsInstance<BrowseEntryRemote.Directory>().single().coverFileName,
        )

        val nested = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Books", isDirectory = true)),
            childPeeks = mapOf(
                "Books" to listOf(RemoteChild(name = "Vol", isDirectory = true)),
            ),
            grandPeeks = mapOf(
                "Books/Vol" to listOf(RemoteChild(name = "01.pdf", isDirectory = false)),
            ),
        )
        assertEquals(
            "Vol/01.pdf",
            nested.filterIsInstance<BrowseEntryRemote.Directory>().single().coverFileName,
        )

        val laterSibling = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Books", isDirectory = true)),
            childPeeks = mapOf(
                "Books" to listOf(
                    RemoteChild(name = "A", isDirectory = true),
                    RemoteChild(name = "B", isDirectory = true),
                ),
            ),
            grandPeeks = mapOf(
                "Books/A" to listOf(RemoteChild(name = "notes.txt", isDirectory = false)),
                "Books/B" to listOf(RemoteChild(name = "01.pdf", isDirectory = false)),
            ),
        )
        assertEquals(
            "B/01.pdf",
            laterSibling.filterIsInstance<BrowseEntryRemote.Directory>().single().coverFileName,
        )

        val thirdLeaf = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Books", isDirectory = true)),
            childPeeks = mapOf(
                "Books" to listOf(
                    RemoteChild(name = "A", isDirectory = true),
                    RemoteChild(name = "B", isDirectory = true),
                    RemoteChild(name = "C", isDirectory = true),
                ),
            ),
            grandPeeks = mapOf(
                "Books/A" to listOf(RemoteChild(name = "notes.txt", isDirectory = false)),
                "Books/B" to listOf(RemoteChild(name = "notes.txt", isDirectory = false)),
                "Books/C" to listOf(RemoteChild(name = "02.pdf", isDirectory = false)),
            ),
        )
        assertEquals(
            "C/02.pdf",
            thirdLeaf.filterIsInstance<BrowseEntryRemote.Directory>().single().coverFileName,
        )

        val fourthNotPeeked = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Books", isDirectory = true)),
            childPeeks = mapOf(
                "Books" to listOf(
                    RemoteChild(name = "A", isDirectory = true),
                    RemoteChild(name = "B", isDirectory = true),
                    RemoteChild(name = "C", isDirectory = true),
                    RemoteChild(name = "D", isDirectory = true),
                ),
            ),
            grandPeeks = mapOf(
                "Books/A" to listOf(RemoteChild(name = "notes.txt", isDirectory = false)),
                "Books/D" to listOf(RemoteChild(name = "only.pdf", isDirectory = false)),
            ),
        )
        assertEquals(
            null,
            fourthNotPeeked.filterIsInstance<BrowseEntryRemote.Directory>().single().coverFileName,
        )
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
    fun documentModeShowsLeftoverFilesInFilesSection() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Mix",
            entries = listOf(
                RemoteChild(name = "guide.pdf", isDirectory = false),
                RemoteChild(name = "notes.txt", isDirectory = false),
                RemoteChild(name = "photo.jpg", isDirectory = false),
                RemoteChild(name = "pack.cbz", isDirectory = false),
                RemoteChild(name = "clip.mp4", isDirectory = false),
            ),
            childPeeks = emptyMap(),
        )
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertTrue(docs.any { it.name == "photo.jpg" })
        assertFalse(docs.any { it.name == "pack.cbz" })
        assertFalse(docs.any { it.name == "clip.mp4" })
        val sections = docs.toRemoteBrowseSections(BrowseContentMode.Document)
        assertEquals(listOf("guide.pdf", "notes.txt"), sections.documents.map { it.name }.sorted())
        assertEquals(listOf("photo.jpg"), sections.files.map { it.name })
        assertTrue(sections.galleries.isEmpty())
        assertTrue(sections.videos.isEmpty())
    }

    @Test
    fun plainDocumentInsidePhotoFolderStaysAHiddenLeaf() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Album", isDirectory = true)),
            childPeeks = mapOf(
                "Album" to listOf(
                    RemoteChild(name = "01.jpg", isDirectory = false),
                    RemoteChild(name = "readme.txt", isDirectory = false),
                    RemoteChild(name = "notes.html", isDirectory = false),
                    RemoteChild(name = "memo.docx", isDirectory = false),
                ),
            ),
        )
        val album = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Album" }
        assertEquals(DirPresence.LeafImages, album.presence)
        assertTrue(album.hasDocument)
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertFalse(photo.any { it is BrowseEntryRemote.Directory && it.name == "Album" })
        assertTrue(photo.any { it is BrowseEntryRemote.FolderGallery && it.relativeName == "Album" })
    }

    @Test
    fun archiveInsidePhotoFolderStaysNavigable() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Album", isDirectory = true)),
            childPeeks = mapOf(
                "Album" to listOf(
                    RemoteChild(name = "01.jpg", isDirectory = false),
                    RemoteChild(name = "book.epub", isDirectory = false),
                    RemoteChild(name = "page.pdf", isDirectory = false),
                    RemoteChild(name = "vol.rar", isDirectory = false),
                ),
            ),
        )
        val album = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Album" }
        assertEquals(DirPresence.Navigable, album.presence)
        assertTrue(album.hasGallery)
        assertTrue(album.hasDocument)
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertTrue(photo.any { it is BrowseEntryRemote.Directory && it.name == "Album" })
        assertTrue(photo.any { it is BrowseEntryRemote.FolderGallery && it.relativeName == "Album" })
    }

    @Test
    fun plainDocumentInsideVideoFolderDoesNotMakeItNavigable() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Clip", isDirectory = true)),
            childPeeks = mapOf(
                "Clip" to listOf(
                    RemoteChild(name = "movie.mp4", isDirectory = false),
                    RemoteChild(name = "readme.txt", isDirectory = false),
                    RemoteChild(name = "notes.html", isDirectory = false),
                    RemoteChild(name = "memo.docx", isDirectory = false),
                ),
            ),
        )
        val clip = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Clip" }
        assertEquals(DirPresence.PromotedShell, clip.presence)
        assertFalse(clip.hasVideo)
        assertFalse(clip.hasGallery)
        assertTrue(clip.hasDocument)
        assertTrue(entries.any { it is BrowseEntryRemote.VideoFile && it.virtual })
        val video = entries.filterRemoteByContentMode(BrowseContentMode.Video)
        assertFalse(video.any { it is BrowseEntryRemote.Directory && it.name == "Clip" })
        assertTrue(video.any { it is BrowseEntryRemote.VideoFile })
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertTrue(docs.any { it is BrowseEntryRemote.Directory && it.name == "Clip" })
        assertFalse(docs.any { it is BrowseEntryRemote.VideoFile })
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertFalse(photo.any { it.name == "Clip" })
    }

    @Test
    fun archiveBesideVideoStaysPhotoNavigableOnly() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Clip", isDirectory = true)),
            childPeeks = mapOf(
                "Clip" to listOf(
                    RemoteChild(name = "movie.mp4", isDirectory = false),
                    RemoteChild(name = "vol.zip", isDirectory = false),
                    RemoteChild(name = "book.epub", isDirectory = false),
                ),
            ),
        )
        val clip = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Clip" }
        assertEquals(DirPresence.Navigable, clip.presence)
        assertTrue(clip.hasGallery)
        assertFalse(clip.hasVideo)
        assertTrue(clip.hasDocument)
        assertTrue(entries.any { it is BrowseEntryRemote.VideoFile && it.virtual })
        val video = entries.filterRemoteByContentMode(BrowseContentMode.Video)
        assertFalse(video.any { it is BrowseEntryRemote.Directory && it.name == "Clip" })
        assertTrue(video.any { it is BrowseEntryRemote.VideoFile })
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertTrue(photo.any { it is BrowseEntryRemote.Directory && it.name == "Clip" })
        assertFalse(photo.any { it is BrowseEntryRemote.VideoFile })
    }

    @Test
    fun emptyPdfDemotesToDocumentFileNotGallery() {
        val key = "smb:9:empty-guide.pdf"
        EmptyArchiveRegistry.mark(key)
        val entries = listOf(
            BrowseEntryRemote.ArchiveGallery(name = "empty-guide.pdf", fileName = "empty-guide.pdf"),
            BrowseEntryRemote.ArchiveGallery(name = "pack.cbz", fileName = "pack.cbz"),
        )
        val out = EmptyArchiveRegistry.filterRemoteEntries(entries) { arch ->
            "smb:9:${arch.fileName}"
        }
        assertTrue(out.any { it is BrowseEntryRemote.RegularFile && it.name == "empty-guide.pdf" })
        assertTrue(out.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "pack.cbz" })
        val sections = out.toRemoteBrowseSections(BrowseContentMode.Galleries)
        assertTrue(sections.documents.any { it is BrowseEntryRemote.RegularFile && it.name == "empty-guide.pdf" })
        assertTrue(sections.galleries.any { it.name == "pack.cbz" })
        assertFalse(sections.galleries.any { it.name.endsWith(".pdf") })
        val photo = out.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertFalse(photo.any { it.name == "empty-guide.pdf" })
        val docs = out.filterRemoteByContentMode(BrowseContentMode.Document)
        assertTrue(docs.any { it is BrowseEntryRemote.RegularFile && it.name == "empty-guide.pdf" })
        assertFalse(docs.any { it.name == "pack.cbz" })
    }

    @Test
    fun emptyComicArchiveLeavesPhotoAndDocument() {
        val key = "smb:9:empty-pack.cbz"
        EmptyArchiveRegistry.mark(key)
        val entries = listOf(
            BrowseEntryRemote.ArchiveGallery(name = "empty-pack.cbz", fileName = "empty-pack.cbz"),
            BrowseEntryRemote.RegularFile(name = "photo.jpg", fileName = "photo.jpg"),
            BrowseEntryRemote.RegularFile(name = "notes.txt", fileName = "notes.txt"),
        )
        val out = EmptyArchiveRegistry.filterRemoteEntries(entries) { arch ->
            "smb:9:${arch.fileName}"
        }
        assertTrue(out.any { it is BrowseEntryRemote.RegularFile && it.name == "empty-pack.cbz" })
        val photo = out.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertFalse(photo.any { it.name == "empty-pack.cbz" })
        val docs = out.filterRemoteByContentMode(BrowseContentMode.Document)
        assertFalse(docs.any { it.name == "empty-pack.cbz" })
        assertTrue(docs.any { it.name == "photo.jpg" })
        assertTrue(docs.any { it.name == "notes.txt" })
        val folder = out.filterRemoteByContentMode(BrowseContentMode.Folder)
        assertTrue(folder.any { it is BrowseEntryRemote.RegularFile && it.name == "empty-pack.cbz" })
    }

    @Test
    fun packagedEbooksShowInPhotoLooseTextDoesNot() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Books",
            entries = listOf(
                RemoteChild(name = "novel.azw3", isDirectory = false),
                RemoteChild(name = "comic.mobi", isDirectory = false),
                RemoteChild(name = "story.fb2", isDirectory = false),
                RemoteChild(name = "old.AZW", isDirectory = false),
                RemoteChild(name = "guide.pdf", isDirectory = false),
                RemoteChild(name = "notes.txt", isDirectory = false),
                RemoteChild(name = "readme.md", isDirectory = false),
                RemoteChild(name = "page.html", isDirectory = false),
                RemoteChild(name = "memo.docx", isDirectory = false),
            ),
            childPeeks = emptyMap(),
        )
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertEquals(
            listOf("comic.mobi", "guide.pdf", "novel.azw3", "old.AZW", "story.fb2"),
            photo.map { it.name }.sorted(),
        )
        assertTrue(photo.all { it is BrowseEntryRemote.ArchiveGallery })
        val photoSections = photo.toRemoteBrowseSections(BrowseContentMode.Galleries)
        assertEquals(photo.map { it.name }.sorted(), photoSections.galleries.map { it.name }.sorted())
        assertTrue(photoSections.documents.isEmpty())
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(
            listOf(
                "comic.mobi",
                "guide.pdf",
                "memo.docx",
                "notes.txt",
                "novel.azw3",
                "old.AZW",
                "page.html",
                "readme.md",
                "story.fb2",
            ),
            docs.map { it.name }.sorted(),
        )
        assertTrue(docs.any { it is BrowseEntryRemote.RegularFile && it.name == "notes.txt" })
        assertTrue(docs.any { it is BrowseEntryRemote.RegularFile && it.name == "readme.md" })

        val cached = listOf(
            BrowseEntryRemote.RegularFile(name = "cached.azw3", fileName = "cached.azw3"),
            BrowseEntryRemote.RegularFile(name = "notes.txt", fileName = "notes.txt"),
        )
        val cachedPhoto = cached.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertEquals(listOf("cached.azw3"), cachedPhoto.map { it.name })
        assertTrue(cachedPhoto.single() is BrowseEntryRemote.ArchiveGallery)
    }

    @Test
    fun ebookFolderIsAPhotoRouteTextFolderIsNot() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(
                RemoteChild(name = "Kindle", isDirectory = true),
                RemoteChild(name = "Notes", isDirectory = true),
            ),
            childPeeks = mapOf(
                "Kindle" to listOf(RemoteChild(name = "novel.azw3", isDirectory = false)),
                "Notes" to listOf(
                    RemoteChild(name = "readme.txt", isDirectory = false),
                    RemoteChild(name = "readme.md", isDirectory = false),
                ),
            ),
        )
        val kindle = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Kindle" }
        val notes = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Notes" }
        assertTrue(kindle.hasGallery)
        assertTrue(kindle.hasDocument)
        assertEquals(DirPresence.Navigable, kindle.presence)
        assertFalse(notes.hasGallery)
        assertTrue(notes.hasDocument)
        assertEquals(DirPresence.Empty, notes.presence)
        val photo = entries.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertEquals(listOf("Kindle"), photo.map { it.name })
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("Kindle", "Notes"), docs.map { it.name }.sorted())
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

    @Test
    fun zipAsDirTagsArchiveFolderAsDocumentRoute() {
        val entries = classifyRemoteListingWithPeeks(
            currentDirName = "Library",
            entries = listOf(RemoteChild(name = "Zips", isDirectory = true)),
            childPeeks = mapOf(
                "Zips" to listOf(RemoteChild(name = "vol1.zip", isDirectory = false)),
            ),
            zipAsDir = true,
        )
        val zips = entries.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Zips" }
        assertTrue(zips.hasDocument)
        val docs = entries.filterRemoteByContentMode(BrowseContentMode.Document)
        assertEquals(listOf("Zips"), docs.map { it.name })
    }
}
