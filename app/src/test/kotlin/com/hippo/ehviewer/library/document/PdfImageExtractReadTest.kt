package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImageExtractReadTest {
    @Test
    fun largeDctStreamAssemblesFromShortReads() {
        val payload = ByteArray(600 * 1024) { i ->
            when (i) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                else -> (i % 251).toByte()
            }
        }
        val (pdf, streamOffset) = dctImagePdf(payload)
        val parser = PdfParser(ShortReadSource(pdf, maxPerCall = 64 * 1024), pdf.size.toLong())
        val result = parser.extractImageBytesAt(
            streamOffset,
            payload.size.toLong(),
            objNum = 10,
            gen = 0,
        )
        assertTrue(result is PdfParser.DirectExtractResult.Success)
        assertArrayEquals(payload, (result as PdfParser.DirectExtractResult.Success).bytes)
    }

    @Test
    fun transientIoOnPayloadIsRetried() {
        val payload = ByteArray(32 * 1024) { it.toByte() }
        payload[0] = 0xFF.toByte()
        payload[1] = 0xD8.toByte()
        val (pdf, streamOffset) = dctImagePdf(payload)
        val parser = PdfParser(FlakyOnceSource(pdf), pdf.size.toLong())
        val result = parser.extractImageBytesAt(
            streamOffset,
            payload.size.toLong(),
            objNum = 10,
            gen = 0,
        )
        assertTrue(result is PdfParser.DirectExtractResult.Success)
        assertArrayEquals(payload, (result as PdfParser.DirectExtractResult.Success).bytes)
    }

    private fun dctImagePdf(payload: ByteArray): Pair<ByteArray, Long> {
        val header = "%PDF-1.4\n10 0 obj\n<< /Type /XObject /Subtype /Image " +
            "/Width 1200 /Height 1200 /ColorSpace /DeviceRGB /BitsPerComponent 8 " +
            "/Filter /DCTDecode /Length ${payload.size} >>\nstream\n"
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        val pdf = ByteArray(headerBytes.size + payload.size + tail.size)
        headerBytes.copyInto(pdf)
        payload.copyInto(pdf, headerBytes.size)
        tail.copyInto(pdf, headerBytes.size + payload.size)
        return pdf to headerBytes.size.toLong()
    }

    private class ShortReadSource(
        private val bytes: ByteArray,
        private val maxPerCall: Int,
    ) : ArchiveByteSource {
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (offset < 0L || offset >= bytes.size) return 0
            val n = minOf(len, maxPerCall, bytes.size - offset.toInt())
            System.arraycopy(bytes, offset.toInt(), buf, off, n)
            return n
        }
        override fun close() = Unit
    }

    private class FlakyOnceSource(private val bytes: ByteArray) : ArchiveByteSource {
        private var failed = false
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (offset < 0L || offset >= bytes.size) return 0
            // Fail the first payload-sized read (skip the small dict probe).
            if (!failed && len >= bytes.size / 2) {
                failed = true
                throw IOException("pread failed at $offset")
            }
            val n = minOf(len, bytes.size - offset.toInt())
            System.arraycopy(bytes, offset.toInt(), buf, off, n)
            return n
        }
        override fun close() = Unit
    }
}
