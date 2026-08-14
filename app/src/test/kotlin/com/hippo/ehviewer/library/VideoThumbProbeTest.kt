package com.hippo.ehviewer.library

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbProbeTest {
    @Test
    fun sparseProbeKeepsMoovAtRealOffset() {
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

        val dest = File.createTempFile("thumb-probe", ".mp4")
        try {
            writeVideoThumbProbe(dest, fileSize, head, tail)
            assertEquals(fileSize, dest.length())

            // moov @ 30628402 in this clip (see box dump). Must match the original.
            val moovAt = 30_628_402L
            val want = ByteArray(8)
            val got = ByteArray(8)
            RandomAccessFile(src, "r").use {
                it.seek(moovAt)
                it.readFully(want)
            }
            RandomAccessFile(dest, "r").use {
                it.seek(moovAt)
                it.readFully(got)
            }
            assertArrayEquals("moov header must sit at the real EOF offset", want, got)
            assertEquals("moov", got.decodeToString(4, 8))
        } finally {
            dest.delete()
        }
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
