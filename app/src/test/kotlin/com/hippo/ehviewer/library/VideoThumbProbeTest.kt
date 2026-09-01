package com.hippo.ehviewer.library

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbProbeTest {
    @Test
    fun ramProbeKeepsMoovAtRealOffset() {
        val src = sampleMp4()
        assertTrue("sample missing: ${src.absolutePath}", src.isFile)
        val fileSize = src.length()
        val headLen = 2 * 1024 * 1024
        val tailLen = 2 * 1024 * 1024
        val head = src.inputStream().use { it.readNBytes(headLen) }
        val tail = ByteArray(tailLen)
        RandomAccessFile(src, "r").use { raf ->
            raf.seek(fileSize - tailLen)
            raf.readFully(tail)
        }

        // moov @ 30628402 in this clip (see box dump). Must match the original.
        val moovAt = 30_628_402L
        val want = ByteArray(8)
        val got = ByteArray(8)
        RandomAccessFile(src, "r").use {
            it.seek(moovAt)
            it.readFully(want)
        }
        val n = readVideoThumbProbe(fileSize, head, tail, moovAt, got, 0, 8)
        assertEquals(8, n)
        assertArrayEquals("moov header must sit at the real EOF offset", want, got)
        assertEquals("moov", got.decodeToString(4, 8))
    }

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

    private fun sampleMp4(): File {
        val name = "VID20260523162315.mp4"
        val cwd = File(System.getProperty("user.dir")!!)
        return listOf(
            File(cwd, "HDR/$name"),
            File(cwd, "../HDR/$name"),
            File(cwd.parentFile, "HDR/$name"),
        ).firstOrNull { it.isFile } ?: File(cwd, "HDR/$name")
    }
}
