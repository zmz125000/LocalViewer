package com.hippo.ehviewer.image.hdr

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/** PNG integer samples must not pass through an F16 bitmap before a lossless export. */
internal fun pngSourcePixels(input: ByteBuffer, width: Int, height: Int): NativeStillBuffer? {
    val file = input.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    if (file.remaining() < 33) return null
    val depth = file.get(24).toInt()
    if (depth != 8 && depth != 16) return null
    val sampleBytes = depth / 8
    val color = file.get(25).toInt() and 255
    if (color == 3 && depth != 8) return null
    val channels = when (color) {
        0, 3 -> 1
        2 -> 3
        4 -> 2
        6 -> 4
        else -> return null
    }
    check(width in 1..65536 && height in 1..65536 && width.toLong() * height <= 80_000_000)
    check(file.getInt(16) == width && file.getInt(20) == height)
    check(file.get(26).toInt() == 0 && file.get(27).toInt() == 0)
    val interlace = file.get(28).toInt()
    check(interlace in 0..1)
    val chunks = mutableListOf<ByteBuffer>()
    var transparent: IntArray? = null
    var palette: ByteArray? = null
    var paletteAlpha: ByteArray? = null
    file.position(8)
    while (file.remaining() >= 12) {
        val size = file.int.toLong() and 0xffffffffL
        val type = file.int
        check(size <= file.remaining() - 4L)
        if (type == 0x49444154) chunks += file.slice().apply { limit(size.toInt()) } // IDAT
        if (type == 0x504c5445 && size in 3..768 && size % 3 == 0L) palette = ByteArray(size.toInt()).also { file.duplicate().get(it) }
        if (type == 0x74524e53 && color == 3 && size <= 256) paletteAlpha = ByteArray(size.toInt()).also { file.duplicate().get(it) }
        if (type == 0x74524e53 && (color == 0 || color == 2) && size == (if (color == 0) 2 else 6).toLong()) {
            val values = file.slice().order(ByteOrder.BIG_ENDIAN)
            transparent = IntArray(size.toInt() / 2) { (values.short.toInt() and 65535) * if (depth == 8) 257 else 1 }
        }
        file.position(file.position() + size.toInt() + 4)
        if (type == 0x49454e44) break
    }
    val pixels = NativeStillBuffer.allocate(width.toLong() * height * 8)
    try {
        val compressed = object : InputStream() {
            var index = 0
            override fun read(): Int {
                while (index < chunks.size && !chunks[index].hasRemaining()) index++
                return if (index == chunks.size) -1 else chunks[index].get().toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                while (index < chunks.size && !chunks[index].hasRemaining()) index++
                if (index == chunks.size) return -1
                val count = minOf(length, chunks[index].remaining())
                chunks[index].get(bytes, offset, count)
                return count
            }
        }
        DataInputStream(InflaterInputStream(compressed)).use { stream ->
            val passes = if (interlace == 0) {
                arrayOf(intArrayOf(0, 0, 1, 1))
            } else {
                arrayOf(
                    intArrayOf(0, 0, 8, 8),
                    intArrayOf(4, 0, 8, 8),
                    intArrayOf(0, 4, 4, 8),
                    intArrayOf(2, 0, 4, 4),
                    intArrayOf(0, 2, 2, 4),
                    intArrayOf(1, 0, 2, 2),
                    intArrayOf(0, 1, 1, 2),
                )
            }
            val bpp = channels * sampleBytes
            for ((startX, startY, stepX, stepY) in passes) {
                if (startX >= width || startY >= height) continue
                val columns = (width - startX + stepX - 1) / stepX
                var previous = ByteArray(columns * bpp)
                var row = ByteArray(previous.size)
                for (y in startY until height step stepY) {
                    val filter = stream.readUnsignedByte()
                    check(filter in 0..4)
                    stream.readFully(row)
                    for (i in if (filter == 0) IntRange.EMPTY else row.indices) {
                        val left = if (i >= bpp) row[i - bpp].toInt() and 255 else 0
                        val up = previous[i].toInt() and 255
                        val corner = if (i >= bpp) previous[i - bpp].toInt() and 255 else 0
                        val predicted = when (filter) {
                            0 -> 0
                            1 -> left
                            2 -> up
                            3 -> (left + up) / 2
                            else -> {
                                val p = left + up - corner
                                val a = abs(p - left)
                                val b = abs(p - up)
                                val c = abs(p - corner)
                                if (a <= b && a <= c) {
                                    left
                                } else if (b <= c) {
                                    up
                                } else {
                                    corner
                                }
                            }
                        }
                        row[i] = ((row[i].toInt() and 255) + predicted).toByte()
                    }
                    fun sample(at: Int) = if (depth == 8) {
                        (row[at].toInt() and 255) * 257
                    } else {
                        ((row[at].toInt() and 255) shl 8) or (row[at + 1].toInt() and 255)
                    }
                    for (column in 0 until columns) {
                        val src = column * bpp
                        val paletteIndex = if (color == 3) row[src].toInt() and 255 else 0
                        val table = if (color == 3) checkNotNull(palette) else null
                        val r = table?.let { (it[paletteIndex * 3].toInt() and 255) * 257 } ?: sample(src)
                        val g = table?.let { (it[paletteIndex * 3 + 1].toInt() and 255) * 257 }
                            ?: if (color == 2 || color == 6) sample(src + sampleBytes) else r
                        val b = table?.let { (it[paletteIndex * 3 + 2].toInt() and 255) * 257 }
                            ?: if (color == 2 || color == 6) sample(src + sampleBytes * 2) else r
                        val key = transparent
                        val a = when {
                            color == 3 -> (paletteAlpha?.getOrNull(paletteIndex)?.toInt()?.and(255) ?: 255) * 257
                            color == 4 -> sample(src + sampleBytes)
                            color == 6 -> sample(src + sampleBytes * 3)
                            key != null && key[0] == r && (color == 0 || key[1] == g && key[2] == b) -> 0
                            else -> 65535
                        }
                        val dst = (y * width + startX + column * stepX) * 8
                        pixels.buffer.putShort(dst, r.toShort())
                        pixels.buffer.putShort(dst + 2, g.toShort())
                        pixels.buffer.putShort(dst + 4, b.toShort())
                        pixels.buffer.putShort(dst + 6, a.toShort())
                    }
                    val reuse = previous
                    previous = row
                    row = reuse
                }
            }
            check(stream.read() == -1) { "Unexpected trailing PNG pixel data" }
        }
        return pixels
    } catch (error: Throwable) {
        pixels.close()
        throw error
    }
}
