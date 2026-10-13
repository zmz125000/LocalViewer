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
        val body = jpegWithPrecision(precision = 8, width = 1600, height = 1200, pad = 40 * 1024)
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
            val pixels = androidJpegPixels(bytes)
            assertNotNull(file.name, pixels)
            val edge = jpegLongEdge(bytes)
            assertTrue("$file edge=$edge", edge >= 1024)
        }
    }

    @Test
    fun twelveBitStripLosesToSmallerEightBitPreview() = runBlocking {
        val preview = jpegWithPrecision(precision = 8, width = 1024, height = 683, pad = 40 * 1024)
        val strip = jpegWithPrecision(precision = 12, width = 2000, height = 3000, pad = 50 * 1024, marker = 0xC3)
        val previewAt = 256
        val stripAt = previewAt + preview.size
        val file = ByteArray(stripAt + strip.size)
        file[0] = 'I'.code.toByte()
        file[1] = 'I'.code.toByte()
        file[2] = 42
        file[4] = 8
        file[8] = 4
        writeIfdEntry(file, 10, 0x0111, 4, 1, stripAt.toLong())
        writeIfdEntry(file, 22, 0x0117, 4, 1, strip.size.toLong())
        writeIfdEntry(file, 34, 0x0201, 4, 1, previewAt.toLong())
        writeIfdEntry(file, 46, 0x0202, 4, 1, preview.size.toLong())
        preview.copyInto(file, previewAt)
        strip.copyInto(file, stripAt)
        val jpeg = readFile(file)
        assertNotNull(jpeg)
        assertEquals(8, jpegPrecision(jpeg!!))
        assertEquals(1024, jpegLongEdge(jpeg))
    }

    @Test
    fun ifdPastTwoMegabytesStillYieldsThePreview() = runBlocking {
        val preview = jpegWithPrecision(precision = 8, width = 1024, height = 576, pad = 40 * 1024)
        val ifdAt = 2_500_000
        val file = ByteArray(ifdAt + 64)
        file[0] = 'I'.code.toByte()
        file[1] = 'I'.code.toByte()
        file[2] = 42
        file[4] = (ifdAt and 0xff).toByte()
        file[5] = ((ifdAt shr 8) and 0xff).toByte()
        file[6] = ((ifdAt shr 16) and 0xff).toByte()
        file[7] = ((ifdAt shr 24) and 0xff).toByte()
        val previewAt = 64
        preview.copyInto(file, previewAt)
        file[ifdAt] = 2
        writeIfdEntry(file, ifdAt + 2, 0x0201, 4, 1, previewAt.toLong())
        writeIfdEntry(file, ifdAt + 14, 0x0202, 4, 1, preview.size.toLong())
        val jpeg = readFile(file)
        assertNotNull(jpeg)
        assertEquals(preview.size, jpeg!!.size)
        assertEquals(8, jpegPrecision(jpeg))
        assertEquals(1024, jpegLongEdge(jpeg))
    }

    private suspend fun readFile(file: ByteArray): ByteArray? = readEmbeddedRawJpeg(file.size.toLong()) { off, len ->
        if (off < 0 || off >= file.size) {
            null
        } else {
            val n = minOf(len.toLong(), file.size - off).toInt()
            if (n <= 0) null else file.copyOfRange(off.toInt(), off.toInt() + n)
        }
    }

    private fun jpegWithPrecision(precision: Int, width: Int, height: Int, pad: Int, marker: Int = 0xC0): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var left = pad
        while (left > 0) {
            val n = minOf(left, 60000)
            val com = ByteArray(4 + n)
            com[0] = 0xff.toByte()
            com[1] = 0xfe.toByte()
            val seglen = n + 2
            com[2] = ((seglen shr 8) and 0xff).toByte()
            com[3] = (seglen and 0xff).toByte()
            chunks += com
            left -= n
        }
        val sof = byteArrayOf(
            0xff.toByte(),
            marker.toByte(),
            0,
            11,
            precision.toByte(),
            ((height shr 8) and 0xff).toByte(),
            (height and 0xff).toByte(),
            ((width shr 8) and 0xff).toByte(),
            (width and 0xff).toByte(),
            1,
            0x11,
            0,
            0,
        )
        var size = 4 + sof.size
        chunks.forEach { size += it.size }
        val out = ByteArray(size)
        out[0] = 0xff.toByte()
        out[1] = 0xd8.toByte()
        sof.copyInto(out, 2)
        var at = 2 + sof.size
        for (com in chunks) {
            com.copyInto(out, at)
            at += com.size
        }
        out[at] = 0xff.toByte()
        out[at + 1] = 0xd9.toByte()
        return out
    }

    private fun jpegPrecision(jpeg: ByteArray): Int = jpegSof(jpeg)?.first ?: -1

    private fun jpegLongEdge(jpeg: ByteArray): Int {
        val sof = jpegSof(jpeg) ?: return -1
        return maxOf(sof.second, sof.third)
    }

    private fun jpegSof(jpeg: ByteArray): Triple<Int, Int, Int>? {
        if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte()) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xff.toByte()) return null
            while (i < jpeg.size && jpeg[i] == 0xff.toByte()) i++
            if (i >= jpeg.size) return null
            val marker = jpeg[i].toInt() and 0xff
            i++
            if (marker == 0xD9 || marker == 0xDA) return null
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
            if (i + 2 > jpeg.size) return null
            val seg = ((jpeg[i].toInt() and 0xff) shl 8) or (jpeg[i + 1].toInt() and 0xff)
            if (seg < 2) return null
            if (marker in 0xC0..0xC3 || marker == 0xC5 || marker == 0xC6 || marker == 0xC7) {
                if (i + 7 >= jpeg.size) return null
                val precision = jpeg[i + 2].toInt() and 0xff
                val height = ((jpeg[i + 3].toInt() and 0xff) shl 8) or (jpeg[i + 4].toInt() and 0xff)
                val width = ((jpeg[i + 5].toInt() and 0xff) shl 8) or (jpeg[i + 6].toInt() and 0xff)
                return Triple(precision, width, height)
            }
            i += seg
        }
        return null
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
