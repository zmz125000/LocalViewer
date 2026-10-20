package com.hippo.ehviewer.image.hdr

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.hippo.ehviewer.jni.decodeHevcAnnexB

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
        val y: ShortArray,
        val cb: ShortArray,
        val cr: ShortArray,
        val chromaWidth: Int,
        val chromaHeight: Int,
    )

    fun decode(unit: HeifContainer.AccessUnit, preferSoftware: Boolean = false): Picture? {
        if (preferSoftware) decodeSoftware(unit)?.let { return it }
        if (unit.bitDepth >= 10) {
            decodePlatform(unit, p010 = true)?.takeIf { it.bitDepth >= unit.bitDepth }?.let { return it }
        }
        decodePlatform(unit, p010 = false)?.takeIf { it.bitDepth >= unit.bitDepth }?.let { return it }
        return if (preferSoftware) null else decodeSoftware(unit)
    }

    private fun decodeSoftware(unit: HeifContainer.AccessUnit): Picture? = runCatching {
        val info = IntArray(5)
        val samples = decodeHevcAnnexB(unit.csd, unit.picture, info) ?: return null
        val (width, height, depth, cw, ch) = info
        if (width <= 0 || height <= 0 || cw <= 0 || ch <= 0 || depth !in unit.bitDepth..16) return null
        val ySize = width.toLong() * height
        val cSize = cw.toLong() * ch
        if (ySize + 2 * cSize != samples.size.toLong()) return null
        val yEnd = ySize.toInt()
        val cbEnd = (ySize + cSize).toInt()
        Picture(
            width,
            height,
            depth,
            samples.copyOfRange(0, yEnd),
            samples.copyOfRange(yEnd, cbEnd),
            samples.copyOfRange(cbEnd, samples.size),
            cw,
            ch,
        )
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
                    picture = readImage(image)
                    image.close()
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
        val y = readPlane(planes[0], crop.left, crop.top, width, height, tenBit) ?: return null
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val mid = if (tenBit) 512 else 128
        val cb = if (planes.size >= 3) {
            readPlane(planes[1], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, tenBit)
        } else {
            null
        } ?: ShortArray(chromaWidth * chromaHeight) { mid.toShort() }
        val cr = if (planes.size >= 3) {
            readPlane(planes[2], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, tenBit)
        } else {
            null
        } ?: ShortArray(chromaWidth * chromaHeight) { mid.toShort() }
        return Picture(width, height, if (tenBit) 10 else 8, y, cb, cr, chromaWidth, chromaHeight)
    }

    private fun readPlane(
        plane: Image.Plane,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        tenBit: Boolean,
    ): ShortArray? {
        val buffer = plane.buffer.duplicate()
        val row = plane.rowStride
        val pix = plane.pixelStride
        if (row <= 0 || pix <= 0) return null
        val out = ShortArray(width * height)
        val sample = if (tenBit) 2 else 1
        for (y in 0 until height) {
            val rowStart = (top + y) * row
            for (x in 0 until width) {
                val at = rowStart + (left + x) * pix
                if (at < 0 || at + sample > buffer.capacity()) return null
                val value = if (tenBit) {
                    val lo = buffer.get(at).toInt() and 0xff
                    val hi = buffer.get(at + 1).toInt() and 0xff
                    ((hi shl 8) or lo) ushr 6
                } else {
                    buffer.get(at).toInt() and 0xff
                }
                out[y * width + x] = value.toShort()
            }
        }
        return out
    }
}
