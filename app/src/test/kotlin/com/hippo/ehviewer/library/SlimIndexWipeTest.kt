package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlimIndexWipeTest {
    private val comics = BrowseEntryRemote.Directory(
        name = "Comics",
        hasVideo = false,
        hasGallery = true,
        presence = DirPresence.Navigable,
    )
    private val videos = BrowseEntryRemote.Directory(
        name = "Videos",
        hasVideo = true,
        hasGallery = false,
        presence = DirPresence.Navigable,
    )
    private val cached = listOf(
        comics,
        videos,
        BrowseEntryRemote.RegularFile(name = "readme.txt", fileName = "readme.txt"),
    )

    @Test
    fun emptyLiveListing_againstCachedDirs_isUntrusted() {
        assertTrue(isUntrustedSlimLiveListing(cached, emptyList()))
        val plan = planRemoteDirectorySlimRefresh(cached, emptyList())
        assertEquals(setOf("Comics", "Videos"), plan.unreachableDirectoryNames)
        assertTrue(plan.removedDirectoryNames.isEmpty())
        assertTrue(plan.addedDirectories.isEmpty())
    }

    @Test
    fun liveFilesOnly_againstCachedDirs_isUntrusted() {
        val live = listOf(RemoteChild(name = "readme.txt", isDirectory = false))
        assertTrue(isUntrustedSlimLiveListing(cached, live))
    }

    @Test
    fun liveKeepsSomeDirs_isTrustedIncrementalRemoval() {
        val live = listOf(
            RemoteChild(name = "Comics", isDirectory = true),
            RemoteChild(name = "readme.txt", isDirectory = false),
        )
        assertFalse(isUntrustedSlimLiveListing(cached, live))
        val plan = planRemoteDirectorySlimRefresh(cached, live)
        assertEquals(setOf("Videos"), plan.unreachableDirectoryNames)
        assertTrue(plan.removedDirectoryNames.isEmpty())
        assertTrue(plan.addedDirectories.isEmpty())
        assertTrue(plan.recoveredDirectoryNames.isEmpty())
    }

    @Test
    fun emptyCache_emptyLive_isTrusted() {
        assertFalse(isUntrustedSlimLiveListing(emptyList(), emptyList()))
    }

    @Test
    fun liveZipFiles_againstCachedZipAsDirFolders_isTrusted() {
        val zipDir = BrowseEntryRemote.Directory(
            name = "album.zip",
            relativeName = "album.zip",
            hasVideo = false,
            hasGallery = true,
            presence = DirPresence.Navigable,
        )
        val cachedZips = listOf(zipDir)
        val live = listOf(RemoteChild(name = "album.zip", isDirectory = false))
        assertFalse(isUntrustedSlimLiveListing(cachedZips, live))
    }

    @Test
    fun wrapperFolderGalleryCountsAsCachedZipAsDir() {
        val gal = BrowseEntryRemote.FolderGallery(
            name = "园区.zip",
            relativeName = "园区.zip/园区",
            pageCount = 2,
            coverFileName = "01.jpg",
            imageFileNames = listOf("01.jpg", "02.jpg"),
        )
        val live = listOf(RemoteChild(name = "园区.zip", isDirectory = false))
        assertFalse(isUntrustedSlimLiveListing(listOf(gal), live))
        assertEquals(setOf("园区.zip"), ZipAsDirListing.cachedDirectZipAsDirNames(listOf(gal)))
    }

    @Test
    fun liveZipFileOnly_againstCachedRealDirs_isUntrusted() {
        val live = listOf(RemoteChild(name = "album.zip", isDirectory = false))
        assertTrue(isUntrustedSlimLiveListing(cached, live))
    }

    @Test
    fun keepPrevious_whenNextIsEmptyOrShallow() {
        val shallow = listOf(
            BrowseEntryRemote.Directory(
                name = "Comics",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
            BrowseEntryRemote.Directory(
                name = "Videos",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Empty,
            ),
        )
        assertTrue(shouldKeepPreviousFolderIndex(cached, emptyList()))
        assertTrue(shouldKeepPreviousFolderIndex(cached, shallow))
        assertTrue(isShallowIncompleteListing(shallow))
        assertFalse(shouldKeepPreviousFolderIndex(cached, cached))
        assertFalse(shouldKeepPreviousFolderIndex(emptyList(), cached))
    }

    @Test
    fun keepPrevious_whenNextDroppedEveryDirectory() {
        val filesOnly = listOf(
            BrowseEntryRemote.RegularFile(name = "readme.txt", fileName = "readme.txt"),
        )
        assertTrue(shouldKeepPreviousFolderIndex(cached, filesOnly))
    }

    @Test
    fun zipAsDirOffDoesNotKeepZipFakeDirectories() {
        val previous = listOf(
            BrowseEntryRemote.Directory(
                name = "pack.zip",
                relativeName = "pack.zip",
                hasVideo = false,
                hasGallery = true,
                presence = DirPresence.Navigable,
            ),
            BrowseEntryRemote.FolderGallery(
                name = "pack.zip",
                relativeName = "pack.zip",
                pageCount = 2,
                coverFileName = "01.jpg",
                imageFileNames = listOf("01.jpg", "02.jpg"),
            ),
        )
        val next = listOf(
            BrowseEntryRemote.ArchiveGallery(
                name = "pack.zip",
                fileName = "pack.zip",
                parentRelativeName = "",
            ),
        )
        assertTrue(shouldKeepPreviousFolderIndex(previous, next, zipAsDir = true))
        assertFalse(shouldKeepPreviousFolderIndex(previous, next, zipAsDir = false))
        assertTrue(indexKeepDirectoryNames(previous, zipAsDir = true).contains("pack.zip"))
        assertTrue(indexKeepDirectoryNames(previous, zipAsDir = false).isEmpty())
    }

    @Test
    fun selectCached_prefersCompleteDiskOverShallowRam() {
        val shallow = listOf(
            BrowseEntryRemote.Directory(
                name = "Comics",
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Pending,
            ),
        )
        val selected = selectCachedFolderListing(
            ramEntries = shallow,
            ramSessionCurrent = false,
            diskEntries = cached,
        )
        assertEquals(cached, selected?.first)
        assertEquals(false, selected?.second)
    }

    @Test
    fun selectCached_keepsCompleteRamWithoutDisk() {
        val selected = selectCachedFolderListing(
            ramEntries = cached,
            ramSessionCurrent = true,
            diskEntries = null,
        )
        assertEquals(cached to true, selected)
    }

    @Test
    fun slimMarksMissingDirUnreachableAndKeepsClassification() {
        val live = listOf(
            RemoteChild(name = "Comics", isDirectory = true),
            RemoteChild(name = "readme.txt", isDirectory = false),
        )
        val plan = planRemoteDirectorySlimRefresh(cached, live)
        val merged = mergeRemoteDirectorySlimRefresh(cached, plan, emptyList())
        val videos = merged.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Videos" }
        assertTrue(videos.unreachable)
        assertEquals(DirPresence.Navigable, videos.presence)
        assertTrue(videos.hasVideo)
        val comics = merged.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Comics" }
        assertFalse(comics.unreachable)
        val visible = merged.filterRemoteByContentMode(BrowseContentMode.Folder)
        assertTrue(visible.none { it.name == "Videos" })
        assertTrue(visible.any { it.name == "Comics" })
    }

    @Test
    fun slimRecoversUnreachableDirWithoutAddingForClassify() {
        val unreachableVideos = videos.copy(unreachable = true)
        val cachedUnreachable = listOf(
            comics,
            unreachableVideos,
            BrowseEntryRemote.RegularFile(name = "readme.txt", fileName = "readme.txt"),
        )
        val live = listOf(
            RemoteChild(name = "Comics", isDirectory = true),
            RemoteChild(name = "Videos", isDirectory = true),
            RemoteChild(name = "readme.txt", isDirectory = false),
        )
        val plan = planRemoteDirectorySlimRefresh(cachedUnreachable, live)
        assertTrue(plan.addedDirectories.isEmpty())
        assertEquals(setOf("Videos"), plan.recoveredDirectoryNames)
        assertTrue(plan.unreachableDirectoryNames.isEmpty())
        val merged = mergeRemoteDirectorySlimRefresh(cachedUnreachable, plan, emptyList())
        val recovered = merged.filterIsInstance<BrowseEntryRemote.Directory>().single { it.name == "Videos" }
        assertFalse(recovered.unreachable)
        assertEquals(DirPresence.Navigable, recovered.presence)
        assertTrue(recovered.hasVideo)
        assertFalse(plan.isUnchanged)
    }

    @Test
    fun alreadyUnreachableStillMissing_isUnchanged() {
        val cachedUnreachable = listOf(
            comics,
            videos.copy(unreachable = true),
        )
        val live = listOf(RemoteChild(name = "Comics", isDirectory = true))
        val plan = planRemoteDirectorySlimRefresh(cachedUnreachable, live)
        assertTrue(plan.isUnchanged)
        assertTrue(plan.addedDirectories.isEmpty())
        assertTrue(plan.recoveredDirectoryNames.isEmpty())
        assertTrue(plan.unreachableDirectoryNames.isEmpty())
    }

    @Test
    fun slimKeepsPromotionsUnderUnreachableDir() {
        val gallery = BrowseEntryRemote.FolderGallery(
            name = "@Videos",
            relativeName = "Videos/album",
            pageCount = 2,
            coverFileName = "01.jpg",
            imageFileNames = listOf("01.jpg", "02.jpg"),
            virtual = true,
        )
        val cachedWithPromo = listOf(comics, videos, gallery)
        val live = listOf(RemoteChild(name = "Comics", isDirectory = true))
        val plan = planRemoteDirectorySlimRefresh(cachedWithPromo, live)
        val merged = mergeRemoteDirectorySlimRefresh(cachedWithPromo, plan, emptyList())
        assertTrue(merged.any { it === gallery || (it is BrowseEntryRemote.FolderGallery && it.relativeName == "Videos/album") })
        val visible = merged.filterRemoteByContentMode(BrowseContentMode.Galleries)
        assertTrue(visible.none { it is BrowseEntryRemote.FolderGallery && it.relativeName.startsWith("Videos") })
        assertTrue(visible.none { it.name == "Videos" })
    }

    @Test
    fun libraryMediaStorePagesDoNotDropDirsOrArchives() {
        val previous = listOf(
            comics,
            BrowseEntryRemote.ArchiveGallery(name = "vol1.cbz", fileName = "vol1.cbz", size = 9L),
            BrowseEntryRemote.RegularFile(name = "old.jpg", fileName = "old.jpg"),
            BrowseEntryRemote.FolderGallery(
                name = "Parent",
                relativeName = "",
                pageCount = 1,
                coverFileName = "old.jpg",
                imageFileNames = listOf("old.jpg"),
            ),
        )
        val merged = FolderGalleryIndex.mergeLibraryFolderPages(
            previous,
            "Parent",
            listOf("01.jpg", "02.jpg"),
        )
        assertTrue(merged.any { it is BrowseEntryRemote.Directory && it.name == "Comics" })
        assertTrue(merged.any { it is BrowseEntryRemote.ArchiveGallery && it.name == "vol1.cbz" })
        val gallery = merged.filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .single { it.relativeName.isEmpty() }
        assertEquals(listOf("01.jpg", "02.jpg"), gallery.imageFileNames)
        assertFalse(merged.any { it.name == "old.jpg" })
        assertTrue(FolderGalleryIndex.isImagePagesOnlyListing(FolderGalleryIndex.listingFromImageNames("P", listOf("a.jpg"))))
        assertFalse(FolderGalleryIndex.isImagePagesOnlyListing(merged))
    }
}
