package com.hippo.ehviewer.library.document

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfRawSamplesTest {
    @Test
    fun indexedRgbLooksUpPaletteOncePerIndex() {
        val palette = byteArrayOf(
            0xff.toByte(), 0x00, 0x00,
            0x00, 0xff.toByte(), 0x00,
            0x00, 0x00, 0xff.toByte(),
        )
        val samples = byteArrayOf(0, 1, 2, 1)
        val pixels = PdfRawSamples.argbFromIndexed(samples, samples.size, palette, baseChannels = 3)
        assertEquals(PdfRawSamples.packRgb(0xff, 0, 0), pixels[0])
        assertEquals(PdfRawSamples.packRgb(0, 0xff, 0), pixels[1])
        assertEquals(PdfRawSamples.packRgb(0, 0, 0xff), pixels[2])
        assertEquals(pixels[1], pixels[3])
    }

    @Test
    fun indexedGrayAndCmykMatchDirectPacking() {
        val grayPal = byteArrayOf(0x10, 0x80.toByte())
        val gray = PdfRawSamples.argbFromIndexed(byteArrayOf(1, 0), 2, grayPal, baseChannels = 1)
        assertEquals(PdfRawSamples.packGray(0x80), gray[0])
        assertEquals(PdfRawSamples.packGray(0x10), gray[1])

        val cmykPal = byteArrayOf(0, 0, 0, 0xff.toByte())
        val cmyk = PdfRawSamples.argbFromIndexed(byteArrayOf(0), 1, cmykPal, baseChannels = 4)
        assertEquals(PdfRawSamples.packCmyk(0, 0, 0, 0xff), cmyk[0])
        assertEquals(PdfRawSamples.packRgb(0, 0, 0), cmyk[0])
    }

    @Test
    fun missingPaletteIndexIsOpaqueBlack() {
        val palette = byteArrayOf(0x11, 0x22, 0x33)
        val pixels = PdfRawSamples.argbFromIndexed(byteArrayOf(2), 1, palette, baseChannels = 3)
        assertEquals(0xff shl 24, pixels[0])
    }

    @Test
    fun fourBitIndexedRowUsesByteFiltersThenNibbleIndices() {
        // Width 4, 4-bit indexed, Colors 1: two bytes per row, PNG filter distance 1.
        // Sub filter: second byte is stored as value - left.
        val encoded = byteArrayOf(
            1,
            0x12,
            (0x34 - 0x12).toByte(),
        )
        val packed = PdfRawSamples.undoPngPredictor(encoded, columns = 4, colors = 1, bits = 4)
        assertEquals(byteArrayOf(0x12, 0x34).toList(), packed!!.toList())
        val indices = PdfRawSamples.expandPackedSamples(packed, columns = 4, colors = 1, bits = 4)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), indices!!.toList())
        val palette = ByteArray(16 * 3)
        palette[4 * 3] = 0xff.toByte()
        val pixels = PdfRawSamples.argbFromIndexed(indices, indices.size, palette, baseChannels = 3)
        assertEquals(PdfRawSamples.packRgb(0xff, 0, 0), pixels[3])
    }

    @Test
    fun fourBitPageRowMatches3050ColumnPredictor15() {
        val columns = 3050
        val rowBytes = PdfRawSamples.pngSampleRowBytes(columns, colors = 1, bits = 4)
        assertEquals(1525, rowBytes)
        assertEquals(1, PdfRawSamples.pngFilterBytes(colors = 1, bits = 4))
        val encoded = ByteArray(rowBytes + 1)
        encoded[0] = 0
        java.util.Arrays.fill(encoded, 1, encoded.size, 0xff.toByte())
        val packed = PdfRawSamples.undoPngPredictor(encoded, columns, colors = 1, bits = 4)
        assertEquals(rowBytes, packed!!.size)
        val indices = PdfRawSamples.expandPackedSamples(packed, columns, colors = 1, bits = 4)
        assertEquals(columns, indices!!.size)
        assertEquals(15, indices[0].toInt() and 0xff)
        assertEquals(15, indices[columns - 1].toInt() and 0xff)
    }

    @Test
    fun eightBitRgbPredictorStillFiltersByComponent() {
        // Sub: each component subtracts the same component of the previous pixel.
        val encoded = byteArrayOf(
            1,
            10,
            20,
            30,
            (1 - 10).toByte(),
            (2 - 20).toByte(),
            (3 - 30).toByte(),
        )
        val samples = PdfRawSamples.undoPngPredictor(encoded, columns = 2, colors = 3, bits = 8)
        assertEquals(byteArrayOf(10, 20, 30, 1, 2, 3).toList(), samples!!.toList())
    }

    @Test
    fun deviceRgbAndGrayExpandInScanOrder() {
        val gray = PdfRawSamples.argbFromGray(byteArrayOf(0x01, 0xfe.toByte()), 2)
        assertEquals(PdfRawSamples.packGray(0x01), gray[0])
        assertEquals(PdfRawSamples.packGray(0xfe), gray[1])

        val rgb = PdfRawSamples.argbFromRgb(byteArrayOf(1, 2, 3, 4, 5, 6), 2)
        assertEquals(PdfRawSamples.packRgb(1, 2, 3), rgb[0])
        assertEquals(PdfRawSamples.packRgb(4, 5, 6), rgb[1])
    }
}
