package com.hippo.ehviewer.ui.screen

import com.hippo.ehviewer.library.BrowseEntry
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.DirPresence
import com.hippo.ehviewer.library.stableGalleryId
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRecentTest {
    private val ctx = LocalBrowseRecentContext(
        rootId = 7L,
        relativePath = "photos",
        zipInnerRel = null,
        zipAsDir = false,
    )

    @Test
    fun recentKeepsSourceOrderOutAndSortsByHistory() {
        val album = dir("Album")
        val trip = dir("Trip")
        val source = listOf(album, trip)
        val history = mapOf(
            stableGalleryId(7L, "browse:photos/Trip") to 20L,
            stableGalleryId(7L, "browse:photos/Album") to 10L,
        )
        val recent = recentBrowseEntries(
            source,
            history,
            gidsOf = { localBrowseHistoryGids(it, ctx) },
            nameOf = { it.name },
        )
        assertEquals(listOf("Trip", "Album"), recent.map { it.name })
        assertEquals(listOf("Album", "Trip"), source.map { it.name })
    }

    @Test
    fun noHistoryHidesEveryItem() {
        val source = listOf(dir("Album"))
        assertTrue(
            recentBrowseEntries(
                source,
                emptyMap(),
                gidsOf = { localBrowseHistoryGids(it, ctx) },
                nameOf = { it.name },
            ).isEmpty(),
        )
    }

    @Test
    fun folderGalleryMatchesReaderGid() {
        val entry = BrowseEntry.FolderGallery(
            name = "Album",
            path = "/sdcard/photos/Album".toPath(),
            relativeName = "Album",
            pageCount = 3,
            coverPath = null,
        )
        val gid = stableGalleryId(7L, "photos/Album")
        val recent = recentBrowseEntries(
            listOf(entry),
            mapOf(gid to 5L),
            gidsOf = { localBrowseHistoryGids(it, ctx) },
            nameOf = { it.name },
        )
        assertEquals(listOf("Album"), recent.map { it.name })
    }

    @Test
    fun localFileMatchesFileHistoryAndLibraryImageId() {
        val path = "/sdcard/photos/a.jpg"
        val file = BrowseEntry.RegularFile(name = "a.jpg", path = path.toPath())
        val fileGid = stableGalleryId(0L, "local-file:$path")
        assertEquals(
            listOf("a.jpg"),
            recentBrowseEntries(
                listOf(file),
                mapOf(fileGid to 3L),
                gidsOf = { localBrowseHistoryGids(it, ctx) },
                nameOf = { it.name },
            ).map { it.name },
        )
    }

    @Test
    fun smbGalleryAndFileUseSourcePrefixes() {
        val gallery = BrowseEntryRemote.FolderGallery(
            name = "Album",
            relativeName = "Album",
            pageCount = 2,
            coverFileName = null,
            imageFileNames = emptyList(),
        )
        val video = BrowseEntryRemote.VideoFile(name = "clip.mp4", fileName = "clip.mp4")
        val history = mapOf(
            stableGalleryId(4L, "smb:share/Album") to 8L,
            stableGalleryId(4L, "smbf:share/clip.mp4") to 9L,
        )
        val recent = recentBrowseEntries(
            listOf(gallery, video),
            history,
            gidsOf = { remoteBrowseHistoryGids(it, sourceId = 4L, relativeDir = "share", smb = true) },
            nameOf = { it.name },
        )
        assertEquals(listOf("clip.mp4", "Album"), recent.map { it.name })
    }

    private fun dir(name: String) = BrowseEntry.Directory(
        name = name,
        path = "/sdcard/photos/$name".toPath(),
        relativeName = name,
        hasVideo = false,
        hasGallery = true,
        presence = DirPresence.Navigable,
    )
}
