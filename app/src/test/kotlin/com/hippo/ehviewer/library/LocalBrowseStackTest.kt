package com.hippo.ehviewer.library

import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrowseStackTest {
    private val root = "/root".toPath()

    @Test
    fun zipFileSegmentIsZipBrowseRoot() {
        val stack = buildLocalBrowseStack(
            rootId = 1L,
            rootDisplayName = "Root",
            rootPath = root,
            relativePath = "Quick Share/pack.zip",
            zipAsDir = true,
        )
        val leaf = stack.last()
        assertEquals("/root/Quick Share/pack.zip", leaf.path)
        assertEquals("Quick Share/pack.zip", leaf.relativePath)
        assertEquals("", leaf.zipInnerRel)
        assertTrue(leaf.isZipBrowse)
        assertFalse(stack[stack.lastIndex - 1].isZipBrowse)
    }

    @Test
    fun zipInnerFolderKeepsZipFilePath() {
        val stack = buildLocalBrowseStack(
            rootId = 1L,
            rootDisplayName = "Root",
            rootPath = root,
            relativePath = "Quick Share/pack.zip/农业设施实景照",
            zipAsDir = true,
        )
        val leaf = stack.last()
        assertEquals("/root/Quick Share/pack.zip", leaf.path)
        assertEquals("Quick Share/pack.zip", leaf.relativePath)
        assertEquals("农业设施实景照", leaf.zipInnerRel)
        assertTrue(leaf.isZipBrowse)
    }

    @Test
    fun pipeRelativePathSplitsLikeSlash() {
        val stack = buildLocalBrowseStack(
            rootId = 1L,
            rootDisplayName = "Root",
            rootPath = root,
            relativePath = "dir/file.zip|Album",
            zipAsDir = true,
        )
        val leaf = stack.last()
        assertEquals("/root/dir/file.zip", leaf.path)
        assertEquals("Album", leaf.zipInnerRel)
    }

    @Test
    fun zipAsDirOffDoesNotEnterZip() {
        val stack = buildLocalBrowseStack(
            rootId = 1L,
            rootDisplayName = "Root",
            rootPath = root,
            relativePath = "dir/file.zip/Album",
            zipAsDir = false,
        )
        val leaf = stack.last()
        assertEquals("/root/dir/file.zip/Album", leaf.path)
        assertNull(leaf.zipInnerRel)
        assertFalse(leaf.isZipBrowse)
    }

    @Test
    fun zipAsDirHistoryRelJoinsZipNameFromParentFrame() {
        val parent = BrowseSession.LocalFrame(
            rootId = 7L,
            path = "/sdcard/Comics",
            title = "Comics",
            relativePath = "Comics",
        )
        assertEquals(
            7L to "Comics/pack.zip|Album",
            LocalHistory.zipAsDirHistoryRel("/sdcard/Comics/pack.zip", "Album", parent),
        )
    }

    @Test
    fun zipAsDirHistoryRelKeepsZipFrameRelative() {
        val zipFrame = BrowseSession.LocalFrame(
            rootId = 7L,
            path = "/sdcard/Comics/pack.zip",
            title = "pack.zip",
            relativePath = "Comics/pack.zip",
            zipInnerRel = "",
        )
        assertEquals(
            7L to "Comics/pack.zip|Album",
            LocalHistory.zipAsDirHistoryRel("/sdcard/Comics/pack.zip", "Album", zipFrame),
        )
    }

    @Test
    fun folderGalleryGidUsesZipPrefix() {
        assertEquals(
            stableGalleryId(1L, "zip:a.zip|Album"),
            LocalHistory.folderGalleryGid(1L, "a.zip|Album"),
        )
        assertEquals(
            stableGalleryId(1L, "Album"),
            LocalHistory.folderGalleryGid(1L, "Album"),
        )
    }
}
