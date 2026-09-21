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
    fun deviceRgbAndGrayExpandInScanOrder() {
        val gray = PdfRawSamples.argbFromGray(byteArrayOf(0x01, 0xfe.toByte()), 2)
        assertEquals(PdfRawSamples.packGray(0x01), gray[0])
        assertEquals(PdfRawSamples.packGray(0xfe), gray[1])

        val rgb = PdfRawSamples.argbFromRgb(byteArrayOf(1, 2, 3, 4, 5, 6), 2)
        assertEquals(PdfRawSamples.packRgb(1, 2, 3), rgb[0])
        assertEquals(PdfRawSamples.packRgb(4, 5, 6), rgb[1])
    }
}
