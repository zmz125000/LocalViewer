package com.hippo.ehviewer.jni

import org.junit.Assert.assertEquals
import org.junit.Test

class GifUtilsTest {
    @Test
    fun heapGifWithZeroDelayIsPatched() {
        val bytes = gifWithDelay(0)
        rewriteHeapGifDelay(bytes, 0, bytes.size)
        assertEquals(10, delayOf(bytes))
    }

    @Test
    fun heapGifWithNormalDelayIsLeftAlone() {
        val bytes = gifWithDelay(5)
        rewriteHeapGifDelay(bytes, 0, bytes.size)
        assertEquals(5, delayOf(bytes))
    }

    @Test
    fun jpegHeapBufferIsNotScannedIntoAWrite() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0x21, 0xF9.toByte(), 4, 0, 0, 0, 0, 0)
        val copy = bytes.copyOf()
        rewriteHeapGifDelay(bytes, 0, bytes.size)
        assertEquals(copy.toList(), bytes.toList())
    }

    private fun gifWithDelay(delay: Int): ByteArray {
        val bytes = ByteArray(16)
        "GIF89a".toByteArray().copyInto(bytes)
        // Previous-block terminator, then a graphic-control extension.
        bytes[6] = 0
        bytes[7] = 0x21
        bytes[8] = 0xF9.toByte()
        bytes[9] = 4
        bytes[10] = 0
        bytes[11] = (delay and 0xFF).toByte()
        bytes[12] = ((delay shr 8) and 0xFF).toByte()
        bytes[13] = 0
        bytes[14] = 0
        return bytes
    }

    private fun delayOf(bytes: ByteArray): Int = (bytes[11].toInt() and 0xFF) or ((bytes[12].toInt() and 0xFF) shl 8)
}
