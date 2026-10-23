package com.hippo.ehviewer.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbProbeTest {
    @Test
    fun ramProbeZeroFillsHolesAndEofIsMinusOne() {
        val head = byteArrayOf(1, 2, 3, 4)
        val tail = byteArrayOf(9, 8)
        val fileSize = 20L
        val buf = ByteArray(8) { -1 }
        assertEquals(8, readVideoThumbProbe(fileSize, head, tail, 6L, buf, 0, 8))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0), buf)
        assertEquals(-1, readVideoThumbProbe(fileSize, head, tail, fileSize, buf, 0, 8))
        assertEquals(2, readVideoThumbProbe(fileSize, head, tail, 18L, buf, 0, 8))
        assertEquals(9.toByte(), buf[0])
        assertEquals(8.toByte(), buf[1])
    }

    @Test
    fun mpegTsNameDetection() {
        assertTrue(isMpegTsVideoName("clip.MTS"))
        assertTrue(isMpegTsVideoName("a/b/c.m2ts"))
        assertTrue(isMpegTsVideoName("broadcast.ts"))
        assertTrue(!isMpegTsVideoName("movie.mp4"))
        assertTrue(!isMpegTsVideoName("video.mkv"))
    }

    @Test
    fun looksLikeMpegTsSyncBytes() {
        val packet = ByteArray(188) { 0 }
        packet[0] = 0x47
        val head = packet + packet + packet
        assertTrue(looksLikeMpegTs(head))
        val notTs = ByteArray(188 * 3) { 0 }
        notTs[0] = 0x00
        assertTrue(!looksLikeMpegTs(notTs))
    }

    /** Browse passes listing size; History probes with size 0 — disk key must match. */
    @Test
    fun cacheIdentityIgnoresKnownSizeBytes() {
        val path = "/storage/emulated/0/Movies/clip.mp4"
        assertEquals(
            VideoThumbnailSource.Local(path, knownSizeBytes = 0L).cacheIdentity,
            VideoThumbnailSource.Local(path, knownSizeBytes = 9_000_000L).cacheIdentity,
        )
        assertEquals(
            VideoThumbnailSource.Smb(7L, "share/a/b.mkv", knownSizeBytes = 0L).cacheIdentity,
            VideoThumbnailSource.Smb(7L, "share/a/b.mkv", knownSizeBytes = 1_234_567L).cacheIdentity,
        )
        assertEquals(
            VideoThumbnailSource.WebDav(3L, "dav/c.mp4", knownSizeBytes = 0L).cacheIdentity,
            VideoThumbnailSource.WebDav(3L, "dav/c.mp4", knownSizeBytes = 99L).cacheIdentity,
        )
        // Slash normalization matches HistoryThumbKey remote encoding.
        assertEquals(
            VideoThumbnailSource.Smb(1L, "a/b.mp4").cacheIdentity,
            VideoThumbnailSource.Smb(1L, "\\a\\b.mp4").cacheIdentity,
        )
    }

    @Test
    fun localVideoPathAliasesPairMediaStoreAndFilesystem() {
        val fs = "/storage/emulated/0/Movies/clip.mp4"
        val ms = "mediastore:/Movies/clip.mp4"
        val fromFs = localVideoPathAliases(fs)
        assertTrue(fromFs.contains(fs))
        assertTrue(fromFs.contains(ms))
        val fromMs = localVideoPathAliases(ms)
        assertTrue(fromMs.contains(ms))
        assertTrue(fromMs.contains(fs))
        assertEquals(
            ms,
            filesystemToMediaStorePath("/sdcard/Movies/clip.mp4"),
        )
        assertEquals(fs, mediaStoreToFilesystemPath(ms))
        assertEquals(null, filesystemToMediaStorePath(ms))
    }
}
