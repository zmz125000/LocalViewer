package com.hippo.ehviewer.gallery

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RamByteSinkTest {
    @Test
    fun takeExactSizeAcrossChunks() {
        val payload = ByteArray(300 * 1024) { i -> (i % 251).toByte() }
        val sink = RamByteSink(chunkSize = 64 * 1024)
        sink.write(payload)
        val out = sink.take()
        assertEquals(payload.size, out.size)
        assertArrayEquals(payload, out)
    }

    @Test
    fun preSizedExactFillDoesNotCopy() {
        val payload = ByteArray(1024) { 0x5A }
        val sink = RamByteSink(expectedSize = payload.size)
        sink.write(payload)
        assertArrayEquals(payload, sink.take())
    }

    @Test
    fun growsPastExpectedWithoutDoublingToPowerOfTwo() {
        val payload = ByteArray(20 * 1024 + 13) { 7 }
        val sink = RamByteSink(expectedSize = 16 * 1024, chunkSize = 4 * 1024)
        sink.write(payload)
        assertArrayEquals(payload, sink.take())
    }

    @Test
    fun matchesByteArrayOutputStreamContents() {
        val payload = ByteArray(50_000) { i -> i.toByte() }
        val bos = ByteArrayOutputStream()
        bos.write(payload)
        val sink = RamByteSink(chunkSize = 7_000)
        sink.write(payload)
        assertArrayEquals(bos.toByteArray(), sink.take())
    }

    @Test
    fun takeReleasesInternalChunks() {
        val first = ByteArray(80 * 1024) { 1 }
        val second = ByteArray(40 * 1024) { 2 }
        val sink = RamByteSink(chunkSize = 32 * 1024)
        sink.write(first)
        assertArrayEquals(first, sink.take())
        sink.write(second)
        assertArrayEquals(second, sink.take())
    }
}
