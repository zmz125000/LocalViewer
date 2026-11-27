package com.hippo.ehviewer.library

import com.hippo.ehviewer.ui.screen.BrowseSortMode
import com.hippo.ehviewer.ui.screen.sortedForBrowseFolderUi
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualGalleryDisplaySettingsTest {
    private fun img(name: String) = RemoteChild(name = name, isDirectory = false)
    private fun dir(name: String) = RemoteChild(name = name, isDirectory = true)

    @Test
    fun gallerySubfolderLimitControlsPromotionWithoutChangingListing() {
        val entries = listOf(dir("One"), dir("Two"), dir("Three"))
        val childPeeks = mapOf(
            "One" to listOf(dir("A")),
            "Two" to listOf(dir("A"), dir("B")),
            "Three" to listOf(dir("A"), dir("B"), dir("C")),
        )
        val grandPeeks = mapOf(
            "One/A" to listOf(img("1.jpg")),
            "Two/A" to listOf(img("1.jpg")),
            "Two/B" to listOf(img("1.jpg")),
            "Three/A" to listOf(img("1.jpg")),
            "Three/B" to listOf(img("1.jpg")),
            "Three/C" to listOf(img("1.jpg")),
        )
        val listing = classifyRemoteListingWithPeeks("Root", entries, childPeeks, grandPeeks)

        val limit1 = listing.filterRemoteByContentMode(
            BrowseContentMode.Galleries,
            maxVirtualGallerySubfolders = 1,
        )
        assertEquals(
            listOf("@One"),
            limit1.filterIsInstance<BrowseEntryRemote.FolderGallery>().map { it.name },
        )
        assertEquals(
            listOf("Three", "Two"),
            limit1.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )

        val limit2 = listing.filterRemoteByContentMode(
            BrowseContentMode.Galleries,
            maxVirtualGallerySubfolders = 2,
        )
        assertEquals(
            listOf("@One", "@Two-A", "@Two-B"),
            limit2.filterIsInstance<BrowseEntryRemote.FolderGallery>().map { it.name },
        )
        assertEquals(
            listOf("Three"),
            limit2.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )

        val limit3 = listing.filterRemoteByContentMode(
            BrowseContentMode.Galleries,
            maxVirtualGallerySubfolders = 3,
        )
        assertEquals(
            listOf("@One", "@Three-A", "@Three-B", "@Three-C", "@Two-A", "@Two-B"),
            limit3.filterIsInstance<BrowseEntryRemote.FolderGallery>().map { it.name },
        )
        assertTrue(limit3.filterIsInstance<BrowseEntryRemote.Directory>().isEmpty())

        // Demoted gallery-only folders do not leak into Video mode.
        val videoLimit1 = listing.filterRemoteByContentMode(
            BrowseContentMode.Video,
            maxVirtualGallerySubfolders = 1,
        )
        assertTrue(videoLimit1.isEmpty())
    }

    @Test
    fun promotedVideoSubfolderLimitAndDirectSingleVideo() {
        val entries = listOf(dir("DirectMovie"), dir("OneSeason"), dir("TwoSeasons"))
        val childPeeks = mapOf(
            "DirectMovie" to listOf(img("film.mp4")),
            "OneSeason" to listOf(dir("S1")),
            "TwoSeasons" to listOf(dir("S1"), dir("S2")),
        )
        val grandPeeks = mapOf(
            "OneSeason/S1" to listOf(img("ep1.mp4"), img("ep2.mp4")),
            "TwoSeasons/S1" to listOf(img("ep1.mp4")),
            "TwoSeasons/S2" to listOf(img("ep1.mp4"), img("ep2.mp4")),
        )
        val listing = classifyRemoteListingWithPeeks("Videos", entries, childPeeks, grandPeeks)

        val limit1 = listing.filterRemoteByContentMode(
            BrowseContentMode.Video,
            maxVirtualGallerySubfolders = 1,
        )
        assertEquals(
            listOf("@DirectMovie"),
            limit1.filterIsInstance<BrowseEntryRemote.VideoFile>().map { it.name },
        )
        assertEquals(
            listOf("@OneSeason", "TwoSeasons"),
            limit1.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )

        // Demoted video-only folder does not leak into Galleries mode.
        val galleriesLimit1 = listing.filterRemoteByContentMode(
            BrowseContentMode.Galleries,
            maxVirtualGallerySubfolders = 1,
        )
        assertTrue(galleriesLimit1.isEmpty())

        val limit2 = listing.filterRemoteByContentMode(
            BrowseContentMode.Video,
            maxVirtualGallerySubfolders = 2,
        )
        assertEquals(
            listOf("@DirectMovie", "@TwoSeasons-S1"),
            limit2.filterIsInstance<BrowseEntryRemote.VideoFile>().map { it.name },
        )
        assertEquals(
            listOf("@OneSeason", "@TwoSeasons-S2"),
            limit2.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )

        // Turning off virtual galleries hides direct single-video promotion and restores DirectMovie dir.
        val virtualOff = listing.filterRemoteByContentMode(
            BrowseContentMode.Video,
            showVirtualGalleries = false,
            maxVirtualGallerySubfolders = 3,
        )
        assertTrue(virtualOff.filterIsInstance<BrowseEntryRemote.VideoFile>().isEmpty())
        assertEquals(
            listOf("DirectMovie", "OneSeason", "TwoSeasons"),
            virtualOff.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )
    }

    @Test
    fun singleSubfolderWithImagesAndVideoCountsAsOneSubfolder() {
        val entries = listOf(dir("Combo"), dir("Split"))
        val childPeeks = mapOf(
            "Combo" to listOf(dir("Only")),
            "Split" to listOf(dir("Pics"), dir("Clip")),
        )
        val grandPeeks = mapOf(
            "Combo/Only" to listOf(img("1.jpg"), img("v.mp4")),
            "Split/Pics" to listOf(img("1.jpg")),
            "Split/Clip" to listOf(img("v.mp4")),
        )
        val listing = classifyRemoteListingWithPeeks("Root", entries, childPeeks, grandPeeks)

        val mediaLimit1 = listing.filterRemoteByContentMode(
            BrowseContentMode.Media,
            maxVirtualGallerySubfolders = 1,
        )
        assertEquals(
            listOf("@Combo"),
            mediaLimit1.filterIsInstance<BrowseEntryRemote.FolderGallery>().map { it.name },
        )
        assertEquals(
            listOf("@Combo-Only"),
            mediaLimit1.filterIsInstance<BrowseEntryRemote.VideoFile>().map { it.name },
        )
        assertEquals(
            listOf("Split"),
            mediaLimit1.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )
        assertEquals(
            listOf("Split"),
            listing.filterRemoteByContentMode(BrowseContentMode.Galleries, maxVirtualGallerySubfolders = 1)
                .filterIsInstance<BrowseEntryRemote.Directory>()
                .map { it.name },
        )
        assertEquals(
            listOf("Split"),
            listing.filterRemoteByContentMode(BrowseContentMode.Video, maxVirtualGallerySubfolders = 1)
                .filterIsInstance<BrowseEntryRemote.Directory>()
                .map { it.name },
        )
    }

    @Test
    fun demotedFolderWithDirectVideoAndTwoGalleryLeavesStaysVisibleInGalleriesMode() {
        val entries = listOf(dir("Hybrid"))
        val childPeeks = mapOf(
            "Hybrid" to listOf(img("direct.mp4"), dir("G1"), dir("G2")),
        )
        val grandPeeks = mapOf(
            "Hybrid/G1" to listOf(img("1.jpg")),
            "Hybrid/G2" to listOf(img("2.jpg")),
        )
        val listing = classifyRemoteListingWithPeeks("Root", entries, childPeeks, grandPeeks)

        val galleriesLimit1 = listing.filterRemoteByContentMode(
            BrowseContentMode.Galleries,
            maxVirtualGallerySubfolders = 1,
        )
        assertTrue(galleriesLimit1.filterIsInstance<BrowseEntryRemote.FolderGallery>().isEmpty())
        assertEquals(
            listOf("Hybrid"),
            galleriesLimit1.filterIsInstance<BrowseEntryRemote.Directory>().map { it.name },
        )
    }

    @Test
    fun localEntriesRespectSubfolderLimitForGalleriesAndVideos() {
        val root = "/sdcard/root".toPath()
        val entries = listOf(
            BrowseEntry.Directory(
                name = "Two",
                path = root / "Two",
                relativeName = "Two",
                hasVideo = true,
                hasGallery = false,
                presence = DirPresence.PromotedShell,
            ),
            BrowseEntry.FolderGallery(
                name = "@Two-A",
                path = root / "Two" / "A",
                relativeName = "Two/A",
                pageCount = 5,
                coverPath = null,
                virtual = true,
            ),
            BrowseEntry.VideoFile(
                name = "@Two-B",
                path = root / "Two" / "B" / "clip.mp4",
                relativeName = "Two/B/clip.mp4",
                virtual = true,
            ),
            BrowseEntry.Directory(
                name = "One",
                path = root / "One",
                relativeName = "One",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.PromotedShell,
            ),
            BrowseEntry.FolderGallery(
                name = "@One",
                path = root / "One" / "Only",
                relativeName = "One/Only",
                pageCount = 5,
                coverPath = null,
                virtual = true,
            ),
        )

        val mediaLimit1 = entries.filterByContentMode(
            BrowseContentMode.Media,
            maxVirtualGallerySubfolders = 1,
        )
        assertEquals(
            listOf("@One"),
            mediaLimit1.filterIsInstance<BrowseEntry.FolderGallery>().map { it.name },
        )
        assertTrue(mediaLimit1.filterIsInstance<BrowseEntry.VideoFile>().isEmpty())
        assertEquals(
            listOf("Two"),
            mediaLimit1.filterIsInstance<BrowseEntry.Directory>().map { it.name },
        )

        // Live search (allTypes = true) still honors the subfolder limit.
        val liveSearchLimit1 = entries.filterByContentMode(
            BrowseContentMode.Media,
            maxVirtualGallerySubfolders = 1,
            allTypes = true,
        )
        assertFalse(liveSearchLimit1.any { it.name == "@Two-A" || it.name == "@Two-B" })
        assertTrue(liveSearchLimit1.any { it.name == "@One" })
        assertTrue(liveSearchLimit1.any { it.name == "Two" })
    }

    @Test
    fun virtualGalleryOnTopControlsAlphabeticalInterleaving() {
        data class Row(
            val name: String,
            val virtual: Boolean,
            val date: Long = 0L,
            val size: Long = 0L,
        )
        val rows = listOf(
            Row("aboy", virtual = false),
            Row("@aboyb", virtual = true),
            Row("@agirl", virtual = true),
            Row("afox", virtual = false),
        )

        val onTop = rows.sortedForBrowseFolderUi(
            mode = BrowseSortMode.Name,
            ascending = true,
            nameOf = { it.name },
            dateOf = { it.date },
            sizeOf = { it.size },
            virtualOnTop = true,
            isVirtual = { it.virtual },
        )
        assertEquals(listOf("@aboyb", "@agirl", "aboy", "afox"), onTop.map { it.name })

        val interleaved = rows.sortedForBrowseFolderUi(
            mode = BrowseSortMode.Name,
            ascending = true,
            nameOf = { it.name },
            dateOf = { it.date },
            sizeOf = { it.size },
            virtualOnTop = false,
            isVirtual = { it.virtual },
        )
        assertEquals(listOf("aboy", "@aboyb", "afox", "@agirl"), interleaved.map { it.name })

        val interleavedDesc = rows.sortedForBrowseFolderUi(
            mode = BrowseSortMode.Name,
            ascending = false,
            nameOf = { it.name },
            dateOf = { it.date },
            sizeOf = { it.size },
            virtualOnTop = false,
            isVirtual = { it.virtual },
        )
        assertEquals(listOf("@agirl", "afox", "@aboyb", "aboy"), interleavedDesc.map { it.name })

        // Real non-virtual `@` names keep their literal `@`, while same-name ties stay deterministic.
        val edgeRows = listOf(
            Row("album", virtual = false),
            Row("@album", virtual = true),
            Row("@real", virtual = false),
            Row("beta", virtual = false),
        )
        val edgeSorted = edgeRows.sortedForBrowseFolderUi(
            mode = BrowseSortMode.Name,
            ascending = true,
            nameOf = { it.name },
            dateOf = { it.date },
            sizeOf = { it.size },
            virtualOnTop = false,
            isVirtual = { it.virtual },
        )
        assertEquals(listOf("@real", "@album", "album", "beta"), edgeSorted.map { it.name })

        // Date ties also honor virtualOnTop = false.
        val dateTied = listOf(
            Row("aboy", virtual = false, date = 100L),
            Row("@aboyb", virtual = true, date = 100L),
            Row("afox", virtual = false, date = 100L),
        )
        val byDate = dateTied.sortedForBrowseFolderUi(
            mode = BrowseSortMode.Date,
            ascending = true,
            nameOf = { it.name },
            dateOf = { it.date },
            sizeOf = { it.size },
            virtualOnTop = false,
            isVirtual = { it.virtual },
        )
        assertEquals(listOf("aboy", "@aboyb", "afox"), byDate.map { it.name })
    }
}
