package com.hippo.ehviewer.image

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageMetadataTest {
    @Test
    fun encoderCommentsDoNotIdentifyAGainmap() {
        assertNull(sourceGainmapMarker("Source: google libuhdr v2.0.2, Attrib: GainMap Image".toByteArray()))
        assertEquals("hdrgm:", sourceGainmapMarker("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"".toByteArray()))
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
