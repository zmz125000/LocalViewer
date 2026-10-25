package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.allocateStillBuffer
import com.hippo.ehviewer.jni.captureStillPixels
import com.hippo.ehviewer.jni.releaseStillBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synchronous scratch ownership; no view may escape close(). */
internal class NativeStillBuffer private constructor(val buffer: ByteBuffer) : AutoCloseable {
    private var closed = false
    val size get() = buffer.capacity()
    operator fun get(index: Int): Byte = buffer.get(index)
    operator fun set(index: Int, value: Byte) {
        buffer.put(index, value)
    }

    /** Successful capture transfers malloc ownership to the reader/export handle. */
    fun capture(): ByteArray? {
        check(!closed)
        val token = captureStillPixels(buffer) ?: return null
        closed = true
        return token
    }

    override fun close() {
        if (!closed) {
            closed = true
            releaseStillBuffer(buffer)
        }
    }

    companion object {
        fun allocate(bytes: Long): NativeStillBuffer {
            require(bytes in 1..Int.MAX_VALUE.toLong())
            return own(allocateStillBuffer(bytes.toInt()) ?: throw OutOfMemoryError("Native still scratch: $bytes bytes"))
        }
        fun own(buffer: ByteBuffer): NativeStillBuffer = NativeStillBuffer(buffer.order(ByteOrder.nativeOrder()))
    }
}
