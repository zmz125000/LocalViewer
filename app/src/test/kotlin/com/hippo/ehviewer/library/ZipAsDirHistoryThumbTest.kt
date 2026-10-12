package com.hippo.ehviewer.library

import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipAsDirHistoryThumbTest {
    @Test
    fun dirHistoryUsesPromotedZipGalleryCover() {
        val entries = listOf(
            BrowseEntryRemote.FolderGallery(
                name = "Album",
                relativeName = "pack.zip/Album",
                pageCount = 1,
                coverFileName = "a.jpg",
                imageFileNames = listOf("a.jpg"),
            ),
        )
        val key = LocalHistory.smbBrowseFolderThumbKey(7L, "share", entries)
        assertEquals(HistoryThumbKey.smbZip(7L, "share/pack.zip", "Album/a.jpg"), key)
        val dav = LocalHistory.webDavBrowseFolderThumbKey(7L, "share", entries)
        assertEquals(HistoryThumbKey.webdavZip(7L, "share/pack.zip", "Album/a.jpg"), dav)
    }

    @Test
    fun siblingCoverKeyIsZipMemberNotFakeRemoteFile() {
        val key = LocalHistory.zipOrRemoteThumbKey(
            sourceId = 3L,
            listedDir = "share",
            relativeName = "tree.zip/Album",
            coverFileName = "a.jpg",
            smb = true,
        )
        assertEquals(HistoryThumbKey.smbZip(3L, "share/tree.zip", "Album/a.jpg"), key)
        assertEquals(
            "share/tree.zip" to "Album/a.jpg",
            ZipAsDirListing.zipAsDirCoverParts("share", "tree.zip/Album", "a.jpg"),
        )
    }

    @Test
    fun zipInteriorListingCoverJoinsInnerPrefix() {
        val key = LocalHistory.zipOrRemoteThumbKey(
            sourceId = 3L,
            listedDir = "share/pack.zip",
            relativeName = "Album",
            coverFileName = "a.jpg",
            smb = false,
        )
        assertEquals(HistoryThumbKey.webdavZip(3L, "share/pack.zip", "Album/a.jpg"), key)
    }

    @Test
    fun looseVideoFolderCoverUsesVideoThumbKeyAndSkipsZipMembers() {
        assertEquals(
            HistoryThumbKey.videoSmb(3L, "share/Shows/a.mp4"),
            LocalHistory.zipOrRemoteThumbKey(3L, "share", "Shows", "a.mp4", smb = true),
        )
        assertEquals(
            HistoryThumbKey.videoWebdav(3L, "share/Shows/b.mkv"),
            LocalHistory.zipOrRemoteThumbKey(3L, "share", "Shows", "b.mkv", smb = false),
        )
        assertEquals(
            null,
            LocalHistory.zipOrRemoteThumbKey(3L, "share", "Shows", "old.avi", smb = true),
        )
        assertEquals(
            null,
            LocalHistory.zipOrRemoteThumbKey(3L, "share", "pack.zip", "clip.mp4", smb = true),
        )
        assertEquals(
            HistoryThumbKey.smbZip(3L, "share/pack.zip", "01.jpg"),
            LocalHistory.zipOrRemoteThumbKey(3L, "share", "pack.zip", "01.jpg", smb = true),
        )
        assertEquals(
            null,
            HistoryThumbKey.looseRemoteCoverKey(smb = true, sourceId = 3L, remoteRelativeFile = "Shows/old.wmv"),
        )
        assertEquals(
            HistoryThumbKey.videoSmb(3L, "Shows/a.mp4"),
            HistoryThumbKey.looseRemoteCoverKey(smb = true, sourceId = 3L, remoteRelativeFile = "Shows/a.mp4"),
        )
    }

    @Test
    fun localVideoFolderCoverIsVidLocalNotRawPath() {
        val video = "/sdcard/Movies/Show/a.mp4"
        assertEquals(HistoryThumbKey.videoLocal(video), HistoryThumbKey.coerceVideoCoverKey(video))
        assertEquals(
            HistoryThumbKey.videoLocal(video),
            HistoryThumbKey.coerceVideoCoverKey(HistoryThumbKey.videoLocal(video)),
        )
        assertEquals("/sdcard/Comics/cover.jpg", HistoryThumbKey.coerceVideoCoverKey("/sdcard/Comics/cover.jpg"))
        val entries = listOf(
            BrowseEntry.VideoFile(name = "b.mkv", path = "/tmp/Shows/b.mkv".toPath()),
            BrowseEntry.VideoFile(name = "a.mp4", path = "/tmp/Shows/a.mp4".toPath()),
        )
        assertEquals(
            HistoryThumbKey.videoLocal("/tmp/Shows/b.mkv"),
            LocalHistory.localBrowseFolderThumbKey(
                rootId = 1L,
                relativePath = "Shows",
                currentPath = "/tmp/Shows",
                entries = entries,
            ),
        )
    }
}
