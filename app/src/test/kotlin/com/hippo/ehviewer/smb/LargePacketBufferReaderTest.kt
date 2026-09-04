package com.hippo.ehviewer.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LargePacketBufferReaderTest {
    @Test
    fun readsPacketFromSingleFill() {
        val reader = LargePacketBufferReader(64)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        putFrame(reader, payload)
        assertArrayEquals(payload, reader.readNext())
        assertNull(reader.readNext())
    }

    @Test
    fun reassemblesSplitHeaderAndBody() {
        val reader = LargePacketBufferReader(8)
        val payload = byteArrayOf(9, 8, 7, 6, 5, 4)
        val frame = frame(payload)
        reader.buffer.put(frame, 0, 4)
        assertNull(reader.readNext())
        reader.buffer.put(frame, 4, frame.size - 4)
        assertArrayEquals(payload, reader.readNext())
    }

    @Test
    fun twoPacketsInOneBuffer() {
        val reader = LargePacketBufferReader(64)
        val a = byteArrayOf(1, 1)
        val b = byteArrayOf(2, 2, 2)
        reader.buffer.put(frame(a))
        reader.buffer.put(frame(b))
        assertArrayEquals(a, reader.readNext())
        assertArrayEquals(b, reader.readNext())
        assertNull(reader.readNext())
    }

    @Test
    fun defaultCapacityIsOneMebibyte() {
        assertEquals(1024 * 1024, LargePacketBufferReader.READ_BUFFER_CAPACITY)
    }

    private fun putFrame(reader: LargePacketBufferReader, payload: ByteArray) {
        reader.buffer.put(frame(payload))
    }

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        val n = payload.size
        out[0] = 0
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }
}
