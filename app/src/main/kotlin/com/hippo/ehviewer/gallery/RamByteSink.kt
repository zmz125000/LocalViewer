package com.hippo.ehviewer.gallery

import java.io.OutputStream
import kotlin.math.min

/**
 * Collect a stream into one exact [ByteArray] without [java.io.ByteArrayOutputStream]
 * doubling (16 MiB → 32 MiB). That 32 MiB allocation is the ART OOM seen while
 * scrolling 20 MiB network pages with reader cache disabled.
 */
@PublishedApi
internal class RamByteSink(
    expectedSize: Int = -1,
    private val chunkSize: Int = DEFAULT_CHUNK,
) : OutputStream() {
    private val chunks = ArrayList<ByteArray>(8)
    private var current: ByteArray =
        if (expectedSize in 1..MAX_EXPECTED) ByteArray(expectedSize) else ByteArray(chunkSize)
    private var pos = 0
    private var total = 0

    override fun write(b: Int) {
        if (pos == current.size) flushChunk()
        current[pos++] = b.toByte()
        total++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        }
        var i = 0
        while (i < len) {
            if (pos == current.size) flushChunk()
            val n = min(len - i, current.size - pos)
            System.arraycopy(b, off + i, current, pos, n)
            pos += n
            i += n
            total += n
        }
    }

    @PublishedApi
    internal fun take(): ByteArray {
        if (chunks.isEmpty()) {
            return if (pos == current.size) current else current.copyOf(pos)
        }
        val out = ByteArray(total)
        var o = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, o, c.size)
            o += c.size
        }
        if (pos > 0) System.arraycopy(current, 0, out, o, pos)
        return out
    }

    private fun flushChunk() {
        if (pos == 0) return
        chunks.add(if (pos == current.size) current else current.copyOf(pos))
        current = ByteArray(chunkSize)
        pos = 0
    }

    companion object {
        const val DEFAULT_CHUNK = 256 * 1024
        private const val MAX_EXPECTED = 256 * 1024 * 1024
    }
}
