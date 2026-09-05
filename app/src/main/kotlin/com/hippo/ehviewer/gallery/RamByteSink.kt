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
            if (current.isEmpty()) current = ByteArray(chunkSize.coerceAtLeast(1))
            val n = min(len - i, current.size - pos)
            if (n <= 0) error("RamByteSink: empty buffer")
            System.arraycopy(b, off + i, current, pos, n)
            pos += n
            i += n
            total += n
        }
    }

    @PublishedApi
    internal fun take(): ByteArray {
        val out = if (chunks.isEmpty()) {
            if (pos == current.size) current else current.copyOf(pos)
        } else {
            ByteArray(total).also { dest ->
                var o = 0
                for (c in chunks) {
                    System.arraycopy(c, 0, dest, o, c.size)
                    o += c.size
                }
                if (pos > 0) System.arraycopy(current, 0, dest, o, pos)
            }
        }
        chunks.clear()
        current = ByteArray(chunkSize)
        pos = 0
        total = 0
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
