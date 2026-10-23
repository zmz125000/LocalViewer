package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySourceOwnershipTest {
    @Test
    fun localBrowseAndFolderGalleryFollowTheRootId() {
        val browse = BaseGalleryInfo(
            gid = 1L,
            token = LOCAL_BROWSE_TOKEN,
            title = "Trip",
            uploader = "3\u0000photos/trip",
        )
        val folder = BaseGalleryInfo(
            gid = 2L,
            token = LOCAL_FOLDER_TOKEN,
            title = "Album",
            uploader = "3\u0000photos/trip",
        )
        val other = BaseGalleryInfo(
            gid = 3L,
            token = LOCAL_BROWSE_TOKEN,
            title = "Other",
            uploader = "4\u0000photos",
        )
        assertTrue(historyOwnedByLocalRoot(browse, 3L, null, emptyMap()))
        assertTrue(historyOwnedByLocalRoot(folder, 3L, null, emptyMap()))
        assertFalse(historyOwnedByLocalRoot(other, 3L, null, emptyMap()))
        assertFalse(historyOwnedBySmbSource(browse, 3L))
    }

    @Test
    fun libraryGalleryFollowsTheStoredRoot() {
        val gallery = BaseGalleryInfo(gid = 42L, token = LOCAL_GALLERY_TOKEN, title = "Trip")
        val roots = mapOf(42L to 3L)
        assertTrue(historyOwnedByLocalRoot(gallery, 3L, null, roots))
        assertFalse(historyOwnedByLocalRoot(gallery, 4L, null, roots))
        assertFalse(historyOwnedByLocalRoot(gallery, 3L, null, emptyMap()))
    }

    @Test
    fun filePathUsesTheLongestRemainingRoot() {
        val nested = BaseGalleryInfo(
            gid = 1L,
            token = LOCAL_FILE_TOKEN,
            title = "a.jpg",
            uploader = "/storage/pics/nested/a.jpg",
        )
        val parentFile = BaseGalleryInfo(
            gid = 2L,
            token = LOCAL_ARCHIVE_TOKEN,
            title = "book.zip",
            uploader = "/storage/pics/book.zip",
        )
        val sibling = BaseGalleryInfo(
            gid = 3L,
            token = LOCAL_FILE_TOKEN,
            title = "b.jpg",
            uploader = "/storage/pics2/b.jpg",
        )
        val other = listOf(2L to "/storage/pics/nested")
        assertFalse(historyOwnedByLocalRoot(nested, 1L, "/storage/pics", emptyMap(), other))
        assertTrue(historyOwnedByLocalRoot(nested, 2L, "/storage/pics/nested", emptyMap(), listOf(1L to "/storage/pics")))
        assertTrue(historyOwnedByLocalRoot(parentFile, 1L, "/storage/pics", emptyMap(), other))
        assertFalse(historyOwnedByLocalRoot(sibling, 1L, "/storage/pics", emptyMap(), other))
    }

    @Test
    fun smbAndWebDavStayOnTheirOwnSource() {
        val smb = BaseGalleryInfo(
            gid = 1L,
            token = SMB_FILE_TOKEN,
            title = "clip.mp4",
            uploader = "7\u0000share/clip.mp4",
        )
        val webdav = BaseGalleryInfo(
            gid = 2L,
            token = WEBDAV_ARCHIVE_TOKEN,
            title = "book.zip",
            uploader = "7\u0000docs/book.zip",
        )
        val thumb = BaseGalleryInfo(
            gid = 3L,
            token = LOCAL_GALLERY_TOKEN,
            title = "Album",
            thumbKey = "smb-thumb:7:share/Album/cover.jpg",
        )
        assertTrue(historyOwnedBySmbSource(smb, 7L))
        assertFalse(historyOwnedBySmbSource(smb, 8L))
        assertFalse(historyOwnedByWebDavSource(smb, 7L))
        assertTrue(historyOwnedByWebDavSource(webdav, 7L))
        assertFalse(historyOwnedBySmbSource(webdav, 7L))
        assertTrue(historyOwnedBySmbSource(thumb, 7L))
        assertFalse(historyOwnedByLocalRoot(smb, 7L, null, emptyMap()))
    }

    @Test
    fun removedLibraryGalleryMatchesIdAndFilePath() {
        val library = BaseGalleryInfo(gid = 42L, token = LOCAL_GALLERY_TOKEN, title = "Album")
        val video = BaseGalleryInfo(
            gid = 1L,
            token = LOCAL_FILE_TOKEN,
            title = "clip.mp4",
            uploader = "/storage/pics/clip.mp4",
        )
        val archive = BaseGalleryInfo(
            gid = 2L,
            token = LOCAL_ARCHIVE_TOKEN,
            title = "book.zip",
            uploader = "/storage/pics/book.zip",
        )
        val otherFile = BaseGalleryInfo(
            gid = 3L,
            token = LOCAL_FILE_TOKEN,
            title = "keep.mp4",
            uploader = "/storage/pics/keep.mp4",
        )
        val ids = setOf(42L)
        val paths = setOf("/storage/pics/clip.mp4", "/storage/pics/book.zip")
        assertTrue(historyMatchesRemovedLibraryGallery(library, ids, paths))
        assertTrue(historyMatchesRemovedLibraryGallery(video, ids, paths))
        assertTrue(historyMatchesRemovedLibraryGallery(archive, ids, paths))
        assertFalse(historyMatchesRemovedLibraryGallery(otherFile, ids, paths))
        assertFalse(historyMatchesRemovedLibraryGallery(library, emptySet(), paths))
        assertFalse(historyMatchesRemovedLibraryGallery(video, ids, emptySet()))
    }
}
