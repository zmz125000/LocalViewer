package com.hippo.ehviewer.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfCoverRendererTest {

    @Test
    fun computeThumbDimensions_scalesPortraitToMaxEdge() {
        val (w, h) = PdfCoverRenderer.computeThumbDimensions(595, 842, 768)
        assertEquals(543, w)
        assertEquals(768, h)
    }

    @Test
    fun computeThumbDimensions_scalesLandscapeToMaxEdge() {
        val (w, h) = PdfCoverRenderer.computeThumbDimensions(842, 595, 768)
        assertEquals(768, w)
        assertEquals(543, h)
    }

    @Test
    fun computeThumbDimensions_clampsExtremeAspectRatios() {
        val (w, h) = PdfCoverRenderer.computeThumbDimensions(10_000, 1, 768)
        assertEquals(768, w)
        assertEquals(1, h)
    }

    @Test
    fun readExact_handlesShortReadsAcrossChunks() {
        val data = ByteArray(100) { it.toByte() }
        val source = object : ArchiveByteSource {
            override val size: Long = data.size.toLong()
            override val isRandomAccess: Boolean = true
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (offset < 0 || offset >= data.size) return 0
                val n = minOf(len, 7, data.size - offset.toInt())
                System.arraycopy(data, offset.toInt(), buf, off, n)
                return n
            }
            override fun close() = Unit
        }
        val slice = PdfCoverRenderer.readExact(source, 10L, 25)
        assertArrayEquals(data.copyOfRange(10, 35), slice)
        assertNull(PdfCoverRenderer.readExact(source, 90L, 25))
    }
}
