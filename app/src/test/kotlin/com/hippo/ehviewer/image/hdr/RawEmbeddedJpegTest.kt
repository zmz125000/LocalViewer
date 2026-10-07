package com.hippo.ehviewer.image.hdr

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RawEmbeddedJpegTest {
    @Test
    fun tiffPreviewSpanBeatsARawStrip() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        // Length must clear the preview minimum, so pad after the real SOI.
        val padded = ByteArray(40 * 1024)
        jpeg.copyInto(padded)
        padded[padded.size - 2] = 0xff.toByte()
        padded[padded.size - 1] = 0xd9.toByte()
        val jpegAt = 256
        val file = ByteArray(jpegAt + padded.size + 16)
        // II TIFF, IFD at 8.
        file[0] = 'I'.code.toByte()
        file[1] = 'I'.code.toByte()
        file[2] = 42
        file[4] = 8
        file[8] = 2
        writeIfdEntry(file, 10, 0x0201, 4, 1, jpegAt.toLong())
        writeIfdEntry(file, 22, 0x0202, 4, 1, padded.size.toLong())
        // Strip length is the rest of a pretend 30 MB file, rejected by the cap.
        padded.copyInto(file, jpegAt)
        val located = locateEmbeddedRawJpeg(file, 30L * 1024 * 1024)
        assertEquals(1, located.exact.size)
        assertEquals(jpegAt.toLong(), located.exact[0].offset)
        assertEquals(padded.size, located.exact[0].length)
    }

    @Test
    fun browseThumbSkipsRangeReadUnlessRawAndUncached() = runBlocking {
        var reads = 0
        val jpeg = byteArrayOf(1)
        val read: suspend () -> ByteArray? = {
            reads++
            jpeg
        }
        assertEquals(null, browseEmbeddedRawJpeg("a.jpg", pageAlreadyCached = false, read))
        assertEquals(null, browseEmbeddedRawJpeg("a.cr2", pageAlreadyCached = true, read))
        assertEquals(null, browseEmbeddedRawJpeg("a.nef", pageAlreadyCached = false, null))
        val got = browseEmbeddedRawJpeg("dir/a.CR2", pageAlreadyCached = false, read)
        assertTrue(got === jpeg)
        assertEquals(1, reads)
    }

    @Test
    fun fujiHeaderNamesThePreview() = runBlocking {
        val body = ByteArray(40 * 1024)
        body[0] = 0xff.toByte()
        body[1] = 0xd8.toByte()
        body[2] = 0xff.toByte()
        body[body.size - 2] = 0xff.toByte()
        body[body.size - 1] = 0xd9.toByte()
        val file = ByteArray(148 + body.size)
        val magic = "FUJIFILMCCD-RAW ".toByteArray()
        magic.copyInto(file)
        writeBe32(file, 84, 148)
        writeBe32(file, 88, body.size)
        body.copyInto(file, 148)
        val jpeg = readEmbeddedRawJpeg(file.size.toLong()) { off, len ->
            if (off < 0 || off >= file.size) {
                null
            } else {
                val n = minOf(len, file.size - off.toInt())
                file.copyOfRange(off.toInt(), off.toInt() + n)
            }
        }
        assertNotNull(jpeg)
        assertEquals(body.size, jpeg!!.size)
        assertEquals(0xff.toByte(), jpeg[0])
        assertEquals(0xd8.toByte(), jpeg[1])
    }

    @Test
    fun sampleRawsYieldAJpegPrefix() {
        val root = File("/home/zlx22/LocalViewer/samples")
        assumeTrue(root.isDirectory)
        val raws = root.walkTopDown().filter { file ->
            file.isFile && file.extension.lowercase() in SAMPLE_EXT
        }.toList()
        assumeTrue(raws.isNotEmpty())
        raws.forEach { file ->
            val jpeg = runBlocking {
                RandomAccessFile(file, "r").use { raf ->
                    readEmbeddedRawJpeg(raf.length()) { off, len ->
                        if (off < 0 || off >= raf.length()) return@readEmbeddedRawJpeg null
                        val n = minOf(len.toLong(), raf.length() - off).toInt()
                        val buf = ByteArray(n)
                        raf.seek(off)
                        val got = raf.read(buf)
                        if (got <= 0) {
                            null
                        } else if (got == n) {
                            buf
                        } else {
                            buf.copyOf(got)
                        }
                    }
                }
            }
            assertNotNull(file.name, jpeg)
            val bytes = jpeg!!
            assertTrue(file.name, bytes.size >= 8 * 1024)
            assertTrue(file.name, bytes.size < file.length())
            assertEquals(file.name, 0xff.toByte(), bytes[0])
            assertEquals(file.name, 0xd8.toByte(), bytes[1])
            assertEquals(file.name, 0xff.toByte(), bytes[bytes.size - 2])
            assertEquals(file.name, 0xd9.toByte(), bytes[bytes.size - 1])
        }
    }

    private fun writeIfdEntry(dest: ByteArray, at: Int, tag: Int, type: Int, count: Long, value: Long) {
        dest[at] = (tag and 0xff).toByte()
        dest[at + 1] = ((tag shr 8) and 0xff).toByte()
        dest[at + 2] = (type and 0xff).toByte()
        dest[at + 3] = ((type shr 8) and 0xff).toByte()
        for (i in 0 until 4) dest[at + 4 + i] = ((count shr (8 * i)) and 0xff).toByte()
        for (i in 0 until 4) dest[at + 8 + i] = ((value shr (8 * i)) and 0xff).toByte()
    }

    private fun writeBe32(dest: ByteArray, at: Int, value: Int) {
        dest[at] = ((value shr 24) and 0xff).toByte()
        dest[at + 1] = ((value shr 16) and 0xff).toByte()
        dest[at + 2] = ((value shr 8) and 0xff).toByte()
        dest[at + 3] = (value and 0xff).toByte()
    }

    private companion object {
        val SAMPLE_EXT = setOf("nef", "nrw", "arw", "raf", "orf", "ori", "rw2", "cr3", "cr2", "dng")
    }
}
