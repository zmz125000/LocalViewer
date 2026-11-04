package com.hippo.ehviewer.image

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageMetadataTest {
    @Test
    fun pngCicpIdentifiesSdrSourceInsteadOfTheScRgbWorkingBitmap() {
        val header = pngHeader(9, 13)
        val metadata = encodedColorMetadata(header)
        assertEquals("BT.2020", metadata?.colorSpace)
        assertEquals(ImageTransfer.SRGB, metadata?.transfer)
        assertEquals(false, metadata?.hdr)
        assertEquals("CICP transfer 13", metadata?.rawTransfer)
    }

    @Test
    fun pngCicpPrecedesIccRegardlessOfChunkOrder() {
        // An unusable fallback profile must not hide supported authoritative cICP.
        val iccp = ByteBuffer.allocate(16).putInt(4).put("iCCP".toByteArray()).putInt(0).putInt(0).array()
        val png = pngHeader(9, 13)
        for (header in listOf(png.copyOfRange(0, 33) + iccp + png.copyOfRange(33, png.size), png + iccp)) {
            assertEquals("BT.2020", encodedColorMetadata(header)?.colorSpace)
            assertEquals(ImageTransfer.SRGB, encodedColorMetadata(header)?.transfer)
        }
    }

    @Test
    fun pngSourceTransferDistinguishesSdrPqAndHlg() {
        for ((code, transfer) in listOf(13 to ImageTransfer.SRGB, 16 to ImageTransfer.PQ, 18 to ImageTransfer.HLG)) {
            assertEquals(transfer, encodedColorMetadata(pngHeader(12, code))?.transfer)
            assertEquals("Display P3", encodedColorMetadata(pngHeader(12, code))?.colorSpace)
            assertEquals(code != 13, encodedColorMetadata(pngHeader(12, code))?.hdr)
        }
        assertNull(encodedColorMetadata(pngHeader(9, 2)))
        assertNull(encodedColorMetadata(pngHeader(9, 13).copyOf(43)))
    }

    private fun pngHeader(primaries: Int, transfer: Int): ByteArray = ByteBuffer.allocate(49)
        .putLong(0x89504e470d0a1a0aUL.toLong())
        .putInt(13).put("IHDR".toByteArray()).putInt(2).putInt(1)
        .put(16).put(6).put(0).put(0).put(0).putInt(0)
        .putInt(4).put("cICP".toByteArray())
        .put(primaries.toByte()).put(transfer.toByte()).put(0).put(1).putInt(0).array()

    @Test
    fun encoderCommentsDoNotIdentifyAGainmap() {
        assertNull(sourceGainmapMarker("Source: google libuhdr v2.0.2, Attrib: GainMap Image".toByteArray()))
        assertEquals("hdrgm:", sourceGainmapMarker("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"".toByteArray()))
    }

    @Test
    fun sdrSampleIsNotTaggedAsGainmapByItsEncoderComment() {
        val sample = listOf(File("samples/20220423_085935_sdr.jpg"), File("../samples/20220423_085935_sdr.jpg"))
            .firstOrNull { it.isFile } ?: return
        assertNull(sourceGainmapMarker(sample.inputStream().use { it.readNBytes(256 * 1024) }))
    }

    @Test
    fun unknownHeaderColorSpaceFallsBackToNativeDecode() {
        assertEquals("Display P3", firstKnownColorSpace(" Unknown ", "Display P3", "sRGB"))
        assertEquals("sRGB", firstKnownColorSpace(null, "", "sRGB"))
        assertEquals("BT.2020", firstKnownColorSpace("BT.2020", "sRGB"))
        assertNull(firstKnownColorSpace("Unknown", null, " "))
    }
}
