package com.hippo.ehviewer.image.hdr

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.hippo.ehviewer.jni.decodeHevcAnnexBBuffer
import java.nio.ShortBuffer

/**
 * One HEVC access unit from a HEIF item. Absolute HDR prefers bundled libde265
 * to preserve precision independently of the device's MediaCodec output formats.
 * Apple gain-map auxiliaries also prefer software; platform decoding is a fallback.
 */
internal object HevcDecoder {
    private const val TAG = "HevcDecoder"

    class Picture(
        val width: Int,
        val height: Int,
        /** Actual sample precision (8–16 for software, 8/10 for MediaCodec). */
        val bitDepth: Int,
        val y: ShortBuffer,
        val cb: ShortBuffer,
        val cr: ShortBuffer,
        val chromaWidth: Int,
        val chromaHeight: Int,
        val fullRange: Boolean? = null,
        val matrix: Int? = null,
        private val owner: NativeStillBuffer? = null,
    ) : AutoCloseable {
        override fun close() {
            owner?.close()
        }
    }

    fun decode(unit: HeifContainer.AccessUnit, preferSoftware: Boolean = false): Picture? {
        if (preferSoftware) decodeSoftware(unit)?.let { return it }
        if (unit.bitDepth >= 10) {
            decodePlatform(unit, p010 = true)?.takeIf { it.bitDepth >= unit.bitDepth }?.let { return it }
        }
        decodePlatform(unit, p010 = false)?.takeIf { it.bitDepth >= unit.bitDepth }?.let { return it }
        return if (preferSoftware) null else decodeSoftware(unit)
    }

    private fun decodeSoftware(unit: HeifContainer.AccessUnit): Picture? = runCatching {
        val info = IntArray(7)
        val buffer = decodeHevcAnnexBBuffer(unit.csd, unit.picture, info) ?: return null
        val owner = NativeStillBuffer.own(buffer)
        var transferred = false
        try {
            val samples = owner.buffer.asShortBuffer()
            val (width, height, depth, cw, ch) = info
            if (width <= 0 || height <= 0 || cw <= 0 || ch <= 0 || depth !in unit.bitDepth..16) return null
            val ySize = width.toLong() * height
            val cSize = cw.toLong() * ch
            if (ySize + 2 * cSize != samples.remaining().toLong()) return null
            val yEnd = ySize.toInt()
            val cbEnd = (ySize + cSize).toInt()
            fun plane(start: Int, end: Int) = samples.duplicate().apply {
                position(start)
                limit(end)
            }.slice()
            Picture(
                width, height, depth,
                plane(0, yEnd), plane(yEnd, cbEnd), plane(cbEnd, samples.limit()), cw, ch,
                fullRange = info[5] != 0,
                matrix = info[6].takeIf { it != 2 },
                owner = owner,
            ).also { transferred = true }
        } finally {
            if (!transferred) owner.close()
        }
    }.onFailure { Log.e(TAG, "Software HEVC decode failed ${unit.width}x${unit.height}", it) }.getOrNull()

    private fun decodePlatform(unit: HeifContainer.AccessUnit, p010: Boolean): Picture? = runCatching {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, unit.width, unit.height).apply {
            if (unit.csd.isNotEmpty()) setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(unit.csd))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, unit.picture.size + unit.csd.size + 4096)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (p010) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val input = codec.dequeueInputBuffer(1_000_000)
            if (input < 0) return null
            val buffer = codec.getInputBuffer(input) ?: return null
            buffer.clear()
            if (unit.picture.size > buffer.capacity()) return null
            buffer.put(unit.picture)
            codec.queueInputBuffer(input, 0, unit.picture.size, 0L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            val eos = codec.dequeueInputBuffer(1_000_000)
            if (eos >= 0) codec.queueInputBuffer(eos, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 3_000_000_000L
            var picture: Picture? = null
            while (System.nanoTime() < deadline) {
                val out = codec.dequeueOutputBuffer(info, 20_000)
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (out < 0) continue
                val image = codec.getOutputImage(out)
                if (image != null && info.size > 0) {
                    image.use { picture = readImage(it) }
                }
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(out, false)
                if (picture != null || end) break
            }
            picture
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }.onFailure { Log.e(TAG, "HEVC decode failed ${unit.width}x${unit.height}", it) }.getOrNull()

    private fun readImage(image: Image): Picture? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null
        val tenBit = image.format == ImageFormat.YCBCR_P010
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val ySize = width.toLong() * height
        val cSize = chromaWidth.toLong() * chromaHeight
        val owner = NativeStillBuffer.allocate((ySize + 2 * cSize) * 2)
        var transferred = false
        try {
            val samples = owner.buffer.asShortBuffer()
            fun plane(start: Int, end: Int) = samples.duplicate().apply {
                position(start)
                limit(end)
            }.slice()
            val yEnd = ySize.toInt()
            val cbEnd = (ySize + cSize).toInt()
            val y = plane(0, yEnd)
            val cb = plane(yEnd, cbEnd)
            val cr = plane(cbEnd, samples.limit())
            if (!readPlane(planes[0], crop.left, crop.top, width, height, tenBit, y)) return null
            val mid = (if (tenBit) 512 else 128).toShort()
            if (planes.size < 3 || !readPlane(planes[1], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, tenBit, cb)) {
                for (i in 0 until cb.limit()) cb.put(i, mid)
            }
            if (planes.size < 3 || !readPlane(planes[2], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, tenBit, cr)) {
                for (i in 0 until cr.limit()) cr.put(i, mid)
            }
            return Picture(width, height, if (tenBit) 10 else 8, y, cb, cr, chromaWidth, chromaHeight, owner = owner)
                .also { transferred = true }
        } finally {
            if (!transferred) owner.close()
        }
    }

    private fun readPlane(
        plane: Image.Plane,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        tenBit: Boolean,
        out: ShortBuffer,
    ): Boolean {
        val buffer = plane.buffer.duplicate()
        val row = plane.rowStride
        val pix = plane.pixelStride
        if (row <= 0 || pix <= 0) return false
        val sample = if (tenBit) 2 else 1
        for (y in 0 until height) {
            val rowStart = (top + y) * row
            for (x in 0 until width) {
                val at = rowStart + (left + x) * pix
                if (at < 0 || at + sample > buffer.capacity()) return false
                val value = if (tenBit) {
                    val lo = buffer.get(at).toInt() and 0xff
                    val hi = buffer.get(at + 1).toInt() and 0xff
                    ((hi shl 8) or lo) ushr 6
                } else {
                    buffer.get(at).toInt() and 0xff
                }
                out.put(y * width + x, value.toShort())
            }
        }
        return true
    }
}
