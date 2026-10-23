package com.hippo.ehviewer.image.hdr

import org.junit.Assert.assertEquals
import org.junit.Test

class StillRouteTest {
    @Test(timeout = 5_000)
    fun largeJpegDoesNotRunHeifCicpFallback() {
        // A compressed JPEG can contain these byte pairs without any CICP metadata.
        // The old fallback searched the entire image twice for every such pair.
        val bytes = ByteArray(20 * 1024 * 1024)
        bytes[0] = 0xff.toByte()
        bytes[1] = 0xd8.toByte()
        for (offset in 1024 until bytes.size - 1 step 1024) {
            bytes[offset] = 9
            bytes[offset + 1] = 16
        }
        assertEquals(StillRoute.Platform, classify(bytes, fileNameHint = "cover.jpg"))
    }

    @Test
    fun jpegGainMapStillUsesPlatformGainMapRoute() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + "hdrgm:Version".toByteArray()
        assertEquals(StillRoute.PlatformGainMap, classify(bytes, fileNameHint = "cover.jpg"))
    }

    @Test
    fun heifCicpFallbackStillSelectsNativeHdrDecoder() {
        for ((brand, name, codec) in listOf(
            Triple("avif", "cover.avif", LibCodec.AvifPq),
            Triple("heic", "cover.heic", LibCodec.HevcHdr),
        )) {
            // Minimal ftyp followed by loose nclx metadata: no primary-item precision.
            val bytes = ByteArray(36)
            bytes[3] = 20
            "ftyp".toByteArray().copyInto(bytes, 4)
            brand.toByteArray().copyInto(bytes, 8)
            brand.toByteArray().copyInto(bytes, 16)
            "nclx".toByteArray().copyInto(bytes, 24)
            bytes[31] = 16
            assertEquals(StillRoute.Lib(codec), classify(bytes, fileNameHint = name))
        }
    }
}
