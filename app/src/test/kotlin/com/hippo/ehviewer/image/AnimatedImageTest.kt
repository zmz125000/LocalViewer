package com.hippo.ehviewer.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedImageTest {
    @Test
    fun gifHeadersAreAnimated() {
        assertTrue(looksLikeAnimatedImageHeader("GIF89a".toByteArray()))
        assertTrue(looksLikeAnimatedImageHeader("GIF87a".toByteArray()))
    }

    @Test
    fun jpegAndPlainPngAreNotAnimated() {
        assertFalse(looksLikeAnimatedImageHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        val png = ByteArray(33)
        png[0] = 0x89.toByte()
        png[1] = 0x50
        png[2] = 0x4E
        png[3] = 0x47
        png[4] = 0x0D
        png[5] = 0x0A
        png[6] = 0x1A
        png[7] = 0x0A
        // IHDR, length 13
        png[11] = 13
        png[12] = 'I'.code.toByte()
        png[13] = 'H'.code.toByte()
        png[14] = 'D'.code.toByte()
        png[15] = 'R'.code.toByte()
        assertFalse(looksLikeAnimatedImageHeader(png))
    }

    @Test
    fun vp8xAnimationBitSelectsAnimatedWebP() {
        val header = ByteArray(21)
        "RIFF".encodeToByteArray().copyInto(header, 0)
        "WEBP".encodeToByteArray().copyInto(header, 8)
        "VP8X".encodeToByteArray().copyInto(header, 12)
        header[20] = 0x02
        assertTrue(looksLikeAnimatedImageHeader(header))
        header[20] = 0x00
        assertFalse(looksLikeAnimatedImageHeader(header))
    }

    @Test
    fun staticWebPWithoutVp8xIsNotAnimated() {
        val header = ByteArray(16)
        "RIFF".encodeToByteArray().copyInto(header, 0)
        "WEBP".encodeToByteArray().copyInto(header, 8)
        "VP8 ".encodeToByteArray().copyInto(header, 12)
        assertFalse(looksLikeAnimatedImageHeader(header))
    }

    @Test
    fun apngActlBeforeIdatIsAnimated() {
        val bytes = ByteArray(64)
        bytes[0] = 0x89.toByte()
        bytes[1] = 0x50
        bytes[2] = 0x4E
        bytes[3] = 0x47
        bytes[4] = 0x0D
        bytes[5] = 0x0A
        bytes[6] = 0x1A
        bytes[7] = 0x0A
        bytes[11] = 13
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        // next chunk at 8+12+13 = 33
        val actl = 33
        bytes[actl + 4] = 'a'.code.toByte()
        bytes[actl + 5] = 'c'.code.toByte()
        bytes[actl + 6] = 'T'.code.toByte()
        bytes[actl + 7] = 'L'.code.toByte()
        assertTrue(looksLikeAnimatedImageHeader(bytes))
    }

    @Test
    fun readerKeepsAnimatedAndHdrAtOriginal() {
        assertTrue(readerShouldDecodeOriginal(forceOriginal = false, looksHdr = false, looksAnimated = true))
        assertTrue(readerShouldDecodeOriginal(forceOriginal = false, looksHdr = true, looksAnimated = false))
        assertTrue(readerShouldDecodeOriginal(forceOriginal = true, looksHdr = false, looksAnimated = false))
        assertFalse(readerShouldDecodeOriginal(forceOriginal = false, looksHdr = false, looksAnimated = false))
    }

    @Test
    fun webpFilenameIsTreatedAsPossiblyAnimated() {
        assertTrue(isAnimatedReaderExtension("webp"))
        assertTrue(isAnimatedReaderExtension(".gif"))
        assertFalse(isAnimatedReaderExtension("jpg"))
    }
}
