package com.hippo.ehviewer.image.hdr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeifPrecisionTest {
    @Test
    fun selectsFastPlatformDepthOnlyWhenItPreservesTheFile() {
        assertEquals(HeifDecodePath.Coil, heifDecodePath(HeifPrecision(true, 10, 16, animated = true), true, true))
        assertEquals(HeifDecodePath.Coil, heifDecodePath(HeifPrecision(true, 8, 13), true, true))
        assertEquals(HeifDecodePath.Coil, heifDecodePath(HeifPrecision(true, 10, 13), false, true))
        assertEquals(HeifDecodePath.PlatformDeep, heifDecodePath(HeifPrecision(true, 10, 13), true, false))
        assertEquals(HeifDecodePath.PlatformDeep, heifDecodePath(HeifPrecision(false, 10, 13), true, true))
        assertEquals(HeifDecodePath.Native, heifDecodePath(HeifPrecision(false, 10, 13), true, false))
        for (avif in listOf(true, false)) {
            for (transfer in listOf(16, 18)) {
                assertEquals(HeifDecodePath.Native, heifDecodePath(HeifPrecision(avif, 10, transfer), true, true))
            }
        }
    }

    @Test
    fun animatedAvifIsNotFlattenedIntoAStill() {
        val bytes = container(listOf(pixi(10), color(16)), listOf(1 to listOf(1, 2)))
        // The major brand is at offset 8, and avis indicates a sequence.
        "avis".toByteArray().copyInto(bytes, 8)
        val info = HeifPrecisionProbe.probe(bytes)!!
        assertTrue(info.animated)
        assertFalse(info.highDepth)
        assertEquals(StillRoute.Platform, classify(bytes, fileNameHint = "animated.avif"))
    }

    @Test
    fun primaryPrecisionAndTransferIgnoreTheAuxiliaryImage() {
        val bytes = container(
            listOf(pixi(8), pixi(10), color(13), color(16)),
            listOf(2 to listOf(2, 4), 1 to listOf(1, 3)),
        )
        val info = HeifPrecisionProbe.probe(bytes)!!
        assertEquals(8, info.bitDepth)
        assertEquals(13, info.transfer)
        assertFalse(info.highDepth)
        assertEquals(StillRoute.Platform, classify(bytes, fileNameHint = "photo.avif"))
    }

    @Test
    fun detectsAv1AndHevcPrecisionWithoutPixi() {
        for ((flags, depth) in listOf(0 to 8, 0x40 to 10, 0x60 to 12)) {
            val bytes = container(listOf(box("av1C", byteArrayOf(0x81.toByte(), 0, flags.toByte(), 0))), listOf(1 to listOf(1)))
            assertEquals(depth, HeifPrecisionProbe.probe(bytes)?.bitDepth)
        }
        val hvcc = ByteArray(23).also {
            it[17] = 2
            it[18] = 2
        }
        val bytes = container(listOf(box("hvcC", hvcc)), listOf(1 to listOf(1)), avif = false)
        val info = HeifPrecisionProbe.probe(bytes)!!
        assertFalse(info.avif)
        assertEquals(10, info.bitDepth)
    }

    @Test
    fun softwareChromaRoutingIgnoresAuxiliaryProfiles() {
        fun hvcc(chroma: Int) = box("hvcC", ByteArray(23).also { it[16] = chroma.toByte() })
        val properties = listOf(hvcc(1), hvcc(2), hvcc(3))
        val regular = HeifPrecisionProbe.probe(container(properties, listOf(1 to listOf(1), 2 to listOf(2)), avif = false))!!
        assertFalse(regular.needsSoftware)
        assertEquals(HeifDecodePath.Coil, heifDecodePath(regular, false, true))
        for (index in 2..3) {
            val unsupported = HeifPrecisionProbe.probe(container(properties, listOf(1 to listOf(index)), avif = false))!!
            assertTrue(unsupported.needsSoftware)
            assertEquals(HeifDecodePath.Native, heifDecodePath(unsupported, false, true))
        }
    }

    @Test
    fun readsWidePropertyIndicesAndTruncatedMdat() {
        val properties = List(130) { box("free", ByteArray(0)) } + pixi(10)
        val bytes = container(properties, listOf(1 to listOf(131)), wide = true) + u32(1000000) + "mdat".toByteArray()
        assertEquals(10, HeifPrecisionProbe.probe(bytes)?.bitDepth)
        assertTrue(HeifPrecisionProbe.probe(bytes)!!.highDepth)
    }

    @Test
    fun unknownPrecisionDoesNotAssumeEightBitAndOtherFormatsAreIgnored() {
        val unknown = HeifPrecisionProbe.probe(container(emptyList(), emptyList()))!!
        assertNull(unknown.bitDepth)
        assertTrue(unknown.highDepth)
        assertEquals(HeifDecodePath.Native, heifDecodePath(unknown, true, true))
        assertNull(HeifPrecisionProbe.probe(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertNull(HeifPrecisionProbe.probe(ByteArray(0)))
    }

    @Test
    fun advancedColorKeepsOriginalHeifAvifInsteadOfJpegConversion() {
        for (name in listOf("page.avif", "page.AVIF", "photo.heic", "photo.heif", "photo.hif")) {
            assertTrue(keepHeifPrecisionOriginal(name, true))
            assertFalse(keepHeifPrecisionOriginal(name, false))
        }
        assertFalse(keepHeifPrecisionOriginal("photo.jpg", true))
        assertFalse(keepHeifPrecisionOriginal("photo.jxl", true))
    }

    @Test
    fun realSampleHeadersKeepTheirPrimaryTenBitPrecision() {
        for (rel in listOf("iphone18/50c3d941caaad0f8e437dd25-5k.avif", "iphonec2/DSC05810.avif", "DSC07633.heic")) {
            val file = listOf(File("samples/$rel"), File("../samples/$rel")).firstOrNull { it.isFile } ?: continue
            val info = HeifPrecisionProbe.probe(file.inputStream().use { it.readNBytes(HeifPrecisionProbe.HEADER_BYTES) })!!
            assertEquals(rel, 10, info.bitDepth)
            if (!rel.contains("iphone18")) assertEquals(16, info.transfer)
        }
    }

    private fun pixi(depth: Int) = box("pixi", ByteArray(4) + byteArrayOf(3, depth.toByte(), depth.toByte(), depth.toByte()))
    private fun color(transfer: Int) = box("colr", "nclx".toByteArray() + u16(1) + u16(transfer) + u16(1) + byteArrayOf(0x80.toByte()))
    private fun u16(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())
    private fun u32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun box(type: String, bytes: ByteArray) = u32(bytes.size + 8) + type.toByteArray() + bytes
    private fun container(properties: List<ByteArray>, items: List<Pair<Int, List<Int>>>, avif: Boolean = true, wide: Boolean = false): ByteArray {
        val brand = if (avif) "avif" else "heic"
        val ftyp = box("ftyp", brand.toByteArray() + ByteArray(4) + "mif1".toByteArray())
        val pitm = box("pitm", ByteArray(4) + u16(1))
        val records = items.fold(ByteArray(0)) { bytes, (id, indices) ->
            bytes + u16(id) + byteArrayOf(indices.size.toByte()) + indices.fold(ByteArray(0)) { b, i ->
                b + if (wide) u16(i) else byteArrayOf(i.toByte())
            }
        }
        val ipma = box("ipma", byteArrayOf(0, 0, 0, if (wide) 1 else 0) + u32(items.size) + records)
        val ipco = box("ipco", properties.fold(ByteArray(0)) { a, b -> a + b })
        return ftyp + box("meta", ByteArray(4) + pitm + box("iprp", ipco + ipma))
    }
}
