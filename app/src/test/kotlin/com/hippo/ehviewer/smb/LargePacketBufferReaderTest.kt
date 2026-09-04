package com.hippo.ehviewer.smb

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.InterruptedByTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertFalse(reader.awaitingHeader)
        reader.buffer.put(frame, 4, frame.size - 4)
        assertArrayEquals(payload, reader.readNext())
        assertTrue(reader.awaitingHeader)
    }

    @Test
    fun expectedDisconnectIncludesAndroidBackgroundAbort() {
        assertTrue(isExpectedAsyncDisconnect(IOException("Software caused connection abort")))
        assertTrue(isExpectedAsyncDisconnect(SocketException("Connection reset")))
        assertTrue(isExpectedAsyncDisconnect(AsynchronousCloseException()))
        assertTrue(isExpectedAsyncDisconnect(ClosedChannelException()))
        assertTrue(isExpectedAsyncDisconnect(InterruptedByTimeoutException()))
        assertTrue(isExpectedAsyncDisconnect(EOFException("Connection closed by server")))
        assertTrue(isExpectedAsyncDisconnect(IOException("wrapper", IOException("Software caused connection abort"))))
        assertFalse(isExpectedAsyncDisconnect(IllegalStateException("corrupt packet")))
        assertFalse(isExpectedAsyncDisconnect(IOException("disk full")))
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
