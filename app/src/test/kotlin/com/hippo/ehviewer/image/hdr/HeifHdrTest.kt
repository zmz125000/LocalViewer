package com.hippo.ehviewer.image.hdr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HeifHdrTest {
    @Test
    fun heifFolderHasOneAppleHdrImageAndLosslessAlphaUsesSoftware() {
        val folder = sample("heif/iphone_15_pro.heic").parentFile!!
        assumeTrue("missing HEIF samples", folder.isDirectory)
        for (file in folder.listFiles().orEmpty().filter { it.extension in setOf("heic", "heif") }) {
            val bytes = file.readBytes()
            val parsed = HeifContainer.parse(bytes)!!
            assertTrue("${file.name} primary", parsed.primaryImage() != null)
            assertTrue("${file.name} is not PQ/HLG", parsed.absoluteHdr() == null)
            if (file.name == "iphone_15_pro.heic") {
                assertEquals(3.848f, parsed.appleGainMap()!!.headroom, 0.01f)
            } else {
                assertTrue("${file.name} SDR", parsed.appleGainMap() == null)
            }
        }
        val bytes = sample("heif/Screenshot 2024-10-31 at 08.19.26 Lossless Alpha.heic").readBytes()
        val parsed = HeifContainer.parse(bytes)!!
        assertEquals(900, parsed.primaryImage()!!.width)
        assertEquals(706, parsed.alphaImage()!!.height)
        assertTrue(parsed.primaryImage()!!.icc!!.size >= 128)
        val info = HeifPrecisionProbe.probe(bytes)!!
        assertEquals(8, info.bitDepth)
        assertEquals(2, info.chroma)
        assertTrue(info.needsSoftware)
        assertEquals(HeifDecodePath.Native, heifDecodePath(info, false, true))
        assertEquals(LibCodec.HevcHdr, (classify(bytes, fileNameHint = "screen.heic") as StillRoute.Lib).codec)
    }

    @Test
    fun appleGainMapHeadroomAndAnnexB() {
        val expected = mapOf(
            "hdr-sample.heic" to 7.37f,
            "classic-car.heic" to 5.98f,
            "greyhounds-looking-for-a-table.heic" to 3.48f,
            "old-safe-wall.heic" to 3.35f,
        )
        for ((name, headroom) in expected) {
            val file = sample(name)
            assumeTrue("missing $file", file.isFile)
            val parsed = HeifContainer.parse(file.readBytes())
            val gain = parsed?.appleGainMap()
            assertTrue("$name HDR header", AppleHdr.looksLike(file.readBytes(), 64 * 1024))
            assertTrue("$name parsed", gain != null)
            gain!!
            assertEquals("$name headroom", headroom, gain.headroom, 0.08f)
            assertTrue("$name tiles", gain.tiles.isNotEmpty())
            assertTrue("$name size", gain.width > 0 && gain.height > 0)
            val tile = gain.tiles[0]
            assertTrue("$name csd", tile.csd.size >= 4 && tile.csd[2] == 0.toByte() && tile.csd[3] == 1.toByte())
            assertEquals("$name vps", 32, (tile.csd[4].toInt() shr 1) and 0x3f)
            assertTrue("$name picture", tile.picture.size > 8 && tile.picture[3] == 1.toByte())
        }
    }

    @Test
    fun pqHeicGridIsAbsoluteHdr() {
        val file = sample("DSC07633.heic")
        assumeTrue("missing $file", file.isFile)
        val hdr = HeifContainer.parse(file.readBytes())?.absoluteHdr()
        assertTrue(hdr != null)
        hdr!!
        assertEquals(7952, hdr.width)
        assertEquals(5304, hdr.height)
        assertEquals(16, hdr.transfer)
        assertEquals(54, hdr.tiles.size)
        assertTrue(hdr.tiles.all { it.bitDepth >= 10 })
        // PQ HEIF must use the direct HEVC path even when UHDR bypass is off.
        // It cannot be handed to libavif or the JPEG convert cache.
        for (name in listOf("photo.heic", "photo.heif", "photo.hif")) {
            val route = classify(file.readBytes(), fileNameHint = name)
            assertEquals(LibCodec.HevcHdr, (route as StillRoute.Lib).codec)
            assertTrue(route.needsLibDecode)
            assertFalse(route.needsUhdr)
        }
    }

    @Test
    fun isoGainMapAvifTakesTheLibAvifRoute() {
        val file = sample("iphone18/50c3d941caaad0f8e437dd25-5k.avif")
        assumeTrue("missing $file", file.isFile)
        val bytes = file.readBytes()
        val n = minOf(bytes.size, HDR_SNIFF_BYTES)
        val route = classify(bytes, n, file.name)
        assertEquals(LibCodec.AvifPq, (route as StillRoute.Lib).codec)
        assertTrue(route.needsUhdr)
    }

    private fun sample(rel: String): File {
        val direct = listOf(File("samples/$rel"), File("../samples/$rel"), File("../../samples/$rel"))
        return direct.firstOrNull { it.isFile } ?: File("../samples/$rel")
    }
}
