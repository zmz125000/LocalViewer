package com.hippo.ehviewer.image.hdr

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySourceTest {
    @Test
    fun jxlAndJxrAlwaysNeedConvertEvenFromRam() {
        // ImageDecoder cannot open these; cache-off ByteBuffer must still convert.
        val jxl = byteArrayOf(0xff.toByte(), 0x0a, 0x00, 0x00)
        val jxr = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0xbc.toByte(), 0x01)
        assertTrue(classify(jxl, jxl.size, "page.jxl").needsUhdr)
        assertTrue(classify(jxr, jxr.size, "page.jxr").needsUhdr)
        assertTrue(classify(ByteBuffer.wrap(jxl), "page.bin").needsUhdr)
        assertTrue(classify(ByteBuffer.wrap(jxr), "page.bin").needsUhdr)
    }

    @Test
    fun wrappedRamPageReusesArrayForConvert() {
        val payload = ByteArray(4096) { i -> i.toByte() }
        val buf = ByteBuffer.wrap(payload)
        assertSame(payload, buf.heapBytesForConvert())
    }

    @Test
    fun slicedBufferCopiesForConvert() {
        val payload = ByteArray(16) { i -> i.toByte() }
        val buf = ByteBuffer.wrap(payload, 4, 8)
        val out = buf.heapBytesForConvert()
        assertArrayEquals(payload.copyOfRange(4, 12), out)
    }
}
