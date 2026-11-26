package com.hippo.ehviewer.coil

import com.hippo.ehviewer.image.hdr.PngHdrMetadata
import com.hippo.ehviewer.image.hdr.pngSourcePixels
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frame data stays in the encoded buffer. Only the current frame is inflated. */
internal class ColorApngDecoder private constructor(
    private val input: ByteBuffer,
    private val tags: PngHdrMetadata,
    private val animation: ApngFrames,
) : ReaderFrameDecoder {
    override val loops = animation.loops
    override val retainedPixelBytes = tags.width.toLong() * tags.height * 8
    private val canvas = nativeApngOpen(tags.width, tags.height)
    private var index = 0
    override val encodedBytes = input.capacity()

    init {
        check(canvas != 0L)
    }

    override fun next(restart: Boolean, advanced: Boolean, info: IntArray, boost: FloatArray, delay: IntArray): ByteArray? {
        if (restart) index = 0
        if (index == animation.frames.size) {
            delay[0] = 0
            return null
        }
        val frame = animation.frames[index]
        return pngSourcePixels(input, frame.width, frame.height, frame.chunks)?.use { codes ->
            nativeApngNext(
                canvas, codes.buffer, frame.x, frame.y, frame.width, frame.height, frame.dispose,
                frame.over, index == 0, tags.primaries, tags.transfer, advanced, info, boost,
            )?.also {
                info[6] = tags.depth
                info[8] = tags.width
                info[9] = tags.height
                info[10] = tags.primaries
                info[11] = tags.transfer
                delay[0] = frame.delay
                index++
            }
        }
    }

    override fun close() = nativeApngClose(canvas)

    companion object {
        fun open(input: ByteBuffer, tags: PngHdrMetadata): ColorApngDecoder? = parseApngFrames(input, tags)?.let { ColorApngDecoder(input, tags, it) }
    }
}

internal data class ApngFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val dispose: Int,
    val over: Boolean,
    val delay: Int,
    val chunks: MutableList<ByteBuffer> = mutableListOf(),
)

internal data class ApngFrames(val frames: List<ApngFrame>, val loops: Int)

internal fun parseApngFrames(input: ByteBuffer, tags: PngHdrMetadata): ApngFrames? {
    if (tags.width.toLong() * tags.height > 16L * 1024 * 1024) return null
    val bytes = input.slice().order(ByteOrder.BIG_ENDIAN)
    if (!PngHdrMetadata.isPng(bytes)) return null
    bytes.position(8)
    val frames = mutableListOf<ApngFrame>()
    var expected = -1
    var loops = 0
    var sequence = 0
    var seenIdat = false
    var seenEnd = false
    var firstUsesIdat = false
    while (bytes.remaining() >= 12) {
        val length = bytes.int
        val type = bytes.int
        if (length < 0 || length > bytes.remaining() - 4) return null
        val chunk = bytes.slice().order(ByteOrder.BIG_ENDIAN).apply { limit(length) }
        bytes.position(bytes.position() + length + 4)
        when (type) {
            0x6163544c -> { // acTL
                if (expected >= 0 || seenIdat || length != 8) return null
                expected = chunk.int
                if (expected <= 0) return null
                loops = (chunk.int.toLong() and 0xffffffffL).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            0x6663544c -> { // fcTL
                if (expected < 0 || length != 26 || chunk.int != sequence++ || frames.size >= expected ||
                    frames.lastOrNull()?.chunks?.isEmpty() == true
                ) {
                    return null
                }
                val width = chunk.int
                val height = chunk.int
                val x = chunk.int
                val y = chunk.int
                val numerator = chunk.short.toInt() and 65535
                val denominator = (chunk.short.toInt() and 65535).takeIf { it != 0 } ?: 100
                val dispose = chunk.get().toInt()
                val blend = chunk.get().toInt()
                if (width <= 0 || height <= 0 || x < 0 || y < 0 || x.toLong() + width > tags.width ||
                    y.toLong() + height > tags.height || dispose !in 0..2 || blend !in 0..1
                ) {
                    return null
                }
                if (!seenIdat) {
                    if (width != tags.width || height != tags.height || x != 0 || y != 0) return null
                    firstUsesIdat = true
                }
                frames += ApngFrame(x, y, width, height, dispose, blend == 1, (1000 * numerator / denominator).coerceAtLeast(10))
            }
            0x49444154 -> { // IDAT belongs to the animation only if preceded by fcTL.
                seenIdat = true
                if (firstUsesIdat && frames.size == 1) frames.first().chunks += chunk
            }
            0x66644154 -> { // fdAT
                if (!seenIdat || frames.isEmpty() || (frames.size == 1 && firstUsesIdat) ||
                    length < 4 || chunk.int != sequence++
                ) {
                    return null
                }
                frames.last().chunks += chunk.slice()
            }
            0x49454e44 -> {
                seenEnd = true
                break
            }
        }
    }
    if (!seenEnd || frames.isEmpty() || frames.size != expected || frames.last().chunks.isEmpty()) return null
    return ApngFrames(frames, loops)
}

private external fun nativeApngOpen(width: Int, height: Int): Long
private external fun nativeApngClose(handle: Long)
private external fun nativeApngNext(
    handle: Long,
    codes: ByteBuffer,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    dispose: Int,
    over: Boolean,
    restart: Boolean,
    primaries: Int,
    transfer: Int,
    advanced: Boolean,
    info: IntArray,
    boost: FloatArray,
): ByteArray?
