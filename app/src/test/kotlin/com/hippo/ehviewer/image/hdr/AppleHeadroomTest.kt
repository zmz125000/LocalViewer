package com.hippo.ehviewer.image.hdr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleHeadroomTest {
    @Test
    fun makerNoteUsesItsOwnByteOrder() {
        for (outer in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            for (maker in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
                for (format in listOf(5, 10)) {
                    assertEquals(2.0.pow(1.5909).toFloat(), appleHeadroomFromExif(fixture(outer, maker, format))!!, 0.0001f)
                }
            }
        }
    }

    @Test
    fun rejectsUnknownMakerByteOrderAndTruncatedRational() {
        val bytes = fixture(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN, 10)
        bytes[4 + 44 + 12] = 0
        assertNull(appleHeadroomFromExif(bytes))
        // No complete rational remains after truncation.
        assertNull(appleHeadroomFromExif(fixture(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN, 10).copyOf(90)))
    }

    private fun fixture(outer: ByteOrder, maker: ByteOrder, format: Int): ByteArray {
        // Four-byte HEIF Exif offset, then TIFF: IFD0 -> Exif IFD -> Apple note.
        val bytes = ByteArray(108)
        val tiff = ByteBuffer.wrap(bytes, 4, bytes.size - 4).slice().order(outer)
        tiff.putShort(0, if (outer == ByteOrder.BIG_ENDIAN) 0x4d4d.toShort() else 0x4949.toShort())
        tiff.putShort(2, 42)
        tiff.putInt(4, 8)
        tiff.putShort(8, 1)
        tiff.putShort(10, 0x8769.toShort())
        tiff.putShort(12, 4)
        tiff.putInt(14, 1)
        tiff.putInt(18, 26)
        tiff.putShort(26, 1)
        tiff.putShort(28, 0x927c.toShort())
        tiff.putShort(30, 7)
        tiff.putInt(32, 60)
        tiff.putInt(36, 44)
        "Apple iOS\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4 + 44)
        val note = ByteBuffer.wrap(bytes, 4 + 44, 60).slice().order(maker)
        note.putShort(12, if (maker == ByteOrder.BIG_ENDIAN) 0x4d4d.toShort() else 0x4949.toShort())
        note.putShort(14, 2)
        for ((index, tag) in listOf(33, 48).withIndex()) {
            val entry = 16 + index * 12
            note.putShort(entry, tag.toShort())
            note.putShort(entry + 2, format.toShort())
            note.putInt(entry + 4, 1)
            note.putInt(entry + 8, 44 + index * 8)
            note.putInt(44 + index * 8, 1)
            note.putInt(48 + index * 8, if (index == 0) 2 else 10)
        }
        return bytes
    }
}
