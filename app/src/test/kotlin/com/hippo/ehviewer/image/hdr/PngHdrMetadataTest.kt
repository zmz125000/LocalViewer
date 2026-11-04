package com.hippo.ehviewer.image.hdr

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PngHdrMetadataTest {
    @Test
    fun ordinaryEightBitPngKeepsSystemDecodeWhileDeepAndWidePngUseColorManagement() {
        assertFalse(PngHdrMetadata(2, 1, 8, 1, 13).needsNativeReader)
        assertTrue(PngHdrMetadata(2, 1, 16, 1, 13).needsNativeReader)
        for (depth in listOf(8, 16)) {
            assertTrue(PngHdrMetadata(2, 1, depth, 9, 13).needsNativeReader)
            assertTrue(PngHdrMetadata(2, 1, depth, 12, 13).needsNativeReader)
            assertTrue(PngHdrMetadata(2, 1, depth, 1, 16).needsNativeReader)
            assertTrue(PngHdrMetadata(2, 1, depth, 1, 18).needsNativeReader)
        }
    }

    @Test
    fun sdrPqAndHlgPreservePrimariesAndBothSourcePrecisions() {
        for (transfer in listOf(13, 16, 18)) {
            for (primaries in listOf(1, 9, 12)) {
                for (depth in listOf(8, 16)) {
                    assertEquals(PngHdrMetadata(2, 1, depth, primaries, transfer), PngHdrMetadata.probe(png(depth, tags(primaries, transfer))))
                }
            }
        }
    }

    @Test
    fun untaggedDeepPngKeepsThePlatformPath() {
        assertNull(PngHdrMetadata.probe(png(16)))
        assertNull(PngHdrMetadata.probe(ByteBuffer.wrap(ByteArray(64))))
    }

    @Test
    fun animatedPngIsNeverFlattenedEvenWithHdrTags() {
        val animation = chunk("acTL", ByteArray(8))
        for (transfer in listOf(13, 16, 18)) {
            assertNull(PngHdrMetadata.probe(png(16, tags(9, transfer), animation)))
            assertNull(PngHdrMetadata.probe(png(16, animation, tags(9, transfer))))
        }
    }

    @Test
    fun invalidCicpAndDuplicateTagsDoNotSelectHdr() {
        for (data in listOf(byteArrayOf(9, 2, 0, 1), byteArrayOf(9, 16, 1, 1), byteArrayOf(9, 16, 0, 0), byteArrayOf(2, 16, 0, 1), byteArrayOf(9, 16, 0))) {
            assertNull(PngHdrMetadata.probe(png(16, chunk("cICP", data))))
        }
        assertNull(PngHdrMetadata.probe(png(16, tags(9, 16), tags(9, 18))))
    }

    @Test
    fun chunkBoundariesRejectTruncationAndDoNotScanPixelData() {
        val valid = png(16, tags(9, 16))
        val truncated = valid.array().copyOf(valid.remaining() - 5)
        assertNull(PngHdrMetadata.probe(ByteBuffer.wrap(truncated)))
        val hostile = png(16, tags(9, 16)).array().copyOf()
        ByteBuffer.wrap(hostile).putInt(33, Int.MAX_VALUE)
        assertNull(PngHdrMetadata.probe(ByteBuffer.wrap(hostile)))
        assertNull(PngHdrMetadata.probe(png(16, chunk("IDAT", tags(9, 16)))))
    }

    @Test
    fun usesTheBorrowedBufferPositionAndLimitWithoutChangingThem() {
        val image = png(16, tags(12, 18)).array()
        val buffer = ByteBuffer.allocate(image.size + 20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(7)
        buffer.put(image)
        buffer.limit(buffer.position())
        buffer.position(7)
        assertEquals(PngHdrMetadata(2, 1, 16, 12, 18), PngHdrMetadata.probe(buffer))
        assertEquals(7, buffer.position())
        assertEquals(image.size + 7, buffer.limit())
    }

    private fun tags(primaries: Int, transfer: Int) = chunk("cICP", byteArrayOf(primaries.toByte(), transfer.toByte(), 0, 1))

    private fun png(depth: Int, vararg chunks: ByteArray): ByteBuffer {
        val header = ByteBuffer.allocate(13).putInt(2).putInt(1).put(depth.toByte()).put(6).put(0).put(0).put(0).array()
        val signature = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        return ByteBuffer.wrap(signature + chunk("IHDR", header) + chunks.fold(ByteArray(0)) { a, b -> a + b } + chunk("IDAT", ByteArray(0)))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val bytes = type.toByteArray(Charsets.US_ASCII) + data
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(data.size)
                it.write(bytes)
                it.writeInt(CRC32().apply { update(bytes) }.value.toInt())
            }
        }.toByteArray()
    }
}
