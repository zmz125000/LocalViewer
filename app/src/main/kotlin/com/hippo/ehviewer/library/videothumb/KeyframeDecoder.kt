package com.hippo.ehviewer.library.videothumb

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.ehviewer.core.util.logcat
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One keyframe → scaled ARGB bitmap through the platform [MediaCodec] (ByteBuffer mode,
 * flexible YUV). Every codec call uses a short dequeue timeout, so cancellation and the
 * deadline are checked between calls and the codec is released on the same thread.
 */
object KeyframeDecoder {
    private const val MAX_CONCURRENT_DECODES = 2
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_DECODER_ATTEMPTS = 2
    private const val PTS_STEP_US = 40_000L

    private val slots = Semaphore(MAX_CONCURRENT_DECODES)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_DECODES)

    private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    suspend fun decode(frame: EncodedKeyframe, maxEdge: Int, timeoutMs: Long): Bitmap? = slots.withPermit {
        withContext(decodeDispatcher) {
            val format = inputFormat(frame)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            for (name in decoderNames(format).take(MAX_DECODER_ATTEMPTS)) {
                ensureActive()
                if (System.nanoTime() >= deadline) break
                val bitmap = runCatching { decodeWith(name, format, frame, maxEdge, deadline) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .onFailure { e -> logcat("VideoThumb") { "decoder $name failed: ${e.javaClass.simpleName} ${e.message}" } }
                    .getOrNull()
                if (bitmap != null) return@withContext bitmap
            }
            null
        }
    }

    private fun inputFormat(frame: EncodedKeyframe): MediaFormat {
        val track = frame.track
        val width = track.width.takeIf { it > 0 } ?: 1920
        val height = track.height.takeIf { it > 0 } ?: 1080
        return MediaFormat.createVideoFormat(track.mime, width, height).apply {
            track.csd.forEachIndexed { i, csd -> setByteBuffer("csd-$i", ByteBuffer.wrap(csd)) }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame.accessUnits.maxOf { it.size } + 4096)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
    }

    /** Hardware first (fast, low power), then software as the fallback. */
    private fun decoderNames(format: MediaFormat): List<String> {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        return codecList.codecInfos
            .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) ?: true
                }.getOrDefault(true)
            }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .map { it.name }
    }

    private suspend fun decodeWith(
        name: String,
        format: MediaFormat,
        frame: EncodedKeyframe,
        maxEdge: Int,
        deadline: Long,
    ): Bitmap? {
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val units = frame.accessUnits
            val targetPts = 0L
            var nextInput = 0
            var inputDone = false
            var outputFormat: MediaFormat? = null
            var fallback: Bitmap? = null
            val info = MediaCodec.BufferInfo()
            while (System.nanoTime() < deadline) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        if (nextInput < units.size) {
                            val unit = units[nextInput]
                            val buffer = codec.getInputBuffer(index) ?: error("no input buffer")
                            if (unit.size > buffer.capacity()) error("access unit ${unit.size} > ${buffer.capacity()}")
                            buffer.clear()
                            buffer.put(unit)
                            val flags = if (nextInput == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            codec.queueInputBuffer(index, 0, unit.size, nextInput * PTS_STEP_US, flags)
                            nextInput++
                        } else {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    out >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isTarget = info.presentationTimeUs == targetPts
                        val bitmap = if (info.size > 0 && (isTarget || fallback == null)) {
                            val fmt = outputFormat ?: codec.outputFormat
                            runCatching { toBitmap(codec, out, info, fmt, frame.track, maxEdge) }.getOrNull()
                        } else {
                            null
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (bitmap != null) {
                            if (isTarget) {
                                fallback?.recycle()
                                return bitmap
                            }
                            fallback = bitmap
                        }
                        if (eos) return fallback
                    }
                }
            }
            fallback?.recycle()
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun toBitmap(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        format: MediaFormat,
        track: VideoTrackInfo,
        maxEdge: Int,
    ): Bitmap? {
        val sarW = format.intOrNull(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH) ?: 0
        val sarH = format.intOrNull(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT) ?: 0
        val codecAspect = if (sarW > 0 && sarH > 0) sarW.toFloat() / sarH else 1f
        val pixelAspect = if (track.pixelAspect != 1f) track.pixelAspect else codecAspect
        val matrix = YuvMatrix.pick(
            standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD),
            fullRange = format.intOrNull(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL,
            height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: track.height,
        )
        val image = codec.getOutputImage(index)
        val bitmap = if (image != null) {
            image.use { YuvPlanes.from(it)?.toBitmap(maxEdge, pixelAspect, matrix) }
        } else {
            val buffer = codec.getOutputBuffer(index) ?: return null
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            YuvPlanes.from(buffer.slice(), format)?.toBitmap(maxEdge, pixelAspect, matrix)
        } ?: return null
        if (track.rotationDegrees == 0) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(track.rotationDegrees.toFloat()) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private inline fun <T> Image.use(block: (Image) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}

/** Integer YUV → RGB coefficients (×256). */
internal class YuvMatrix(val yScale: Int, val yOffset: Int, val rv: Int, val gu: Int, val gv: Int, val bu: Int) {
    companion object {
        private val BT601 = YuvMatrix(298, 16, 409, 100, 208, 516)
        private val BT709 = YuvMatrix(298, 16, 459, 55, 136, 541)
        private val BT2020 = YuvMatrix(298, 16, 430, 48, 167, 548)
        private val BT601_FULL = YuvMatrix(256, 0, 359, 88, 183, 454)
        private val BT709_FULL = YuvMatrix(256, 0, 403, 48, 120, 475)
        private val BT2020_FULL = YuvMatrix(256, 0, 377, 42, 146, 482)

        fun pick(standard: Int?, fullRange: Boolean, height: Int): YuvMatrix {
            val kind = when (standard) {
                MediaFormat.COLOR_STANDARD_BT709 -> 709
                MediaFormat.COLOR_STANDARD_BT2020 -> 2020
                MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> 601
                else -> if (height >= 720) 709 else 601
            }
            return when (kind) {
                709 -> if (fullRange) BT709_FULL else BT709
                2020 -> if (fullRange) BT2020_FULL else BT2020
                else -> if (fullRange) BT601_FULL else BT601
            }
        }
    }
}

/**
 * Cropped 4:2:0 picture as three strided planes. 16-bit (P010) samples read their high
 * byte, which is enough for a thumbnail.
 */
internal class YuvPlanes(
    private val y: ByteBuffer,
    private val u: ByteBuffer,
    private val v: ByteBuffer,
    private val yRowStride: Int,
    private val yPixelStride: Int,
    private val uvRowStride: Int,
    private val uvPixelStride: Int,
    private val sampleBytes: Int,
    private val cropLeft: Int,
    private val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
) {
    fun toBitmap(maxEdge: Int, pixelAspect: Float, m: YuvMatrix): Bitmap {
        val displayWidth = cropWidth * pixelAspect
        val scale = minOf(1f, maxEdge / maxOf(displayWidth, cropHeight.toFloat()))
        val outW = (displayWidth * scale).toInt().coerceAtLeast(1)
        val outH = (cropHeight * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(outW * outH)
        val hi = sampleBytes - 1
        for (oy in 0 until outH) {
            val sy = cropTop + (oy * cropHeight / outH)
            val yRow = sy * yRowStride
            val uvRow = (sy / 2) * uvRowStride
            for (ox in 0 until outW) {
                val sx = cropLeft + (ox * cropWidth / outW)
                val luma = y.get(yRow + sx * yPixelStride + hi).toInt() and 0xFF
                val uvAt = uvRow + (sx / 2) * uvPixelStride + hi
                val cb = (u.get(uvAt).toInt() and 0xFF) - 128
                val cr = (v.get(uvAt).toInt() and 0xFF) - 128
                val c = m.yScale * (luma - m.yOffset)
                val r = (c + m.rv * cr + 128) shr 8
                val g = (c - m.gu * cb - m.gv * cr + 128) shr 8
                val b = (c + m.bu * cb + 128) shr 8
                pixels[oy * outW + ox] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /**
         * Layouts a ByteBuffer decoder still writes into [MediaFormat.KEY_COLOR_FORMAT].
         * Same numbers as the deprecated CodecCapabilities YUV420 planar / semi-planar fields.
         * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible] is only a request, not a layout.
         */
        private const val COLOR_FORMAT_YUV420_PLANAR = 19
        private const val COLOR_FORMAT_YUV420_SEMI_PLANAR = 21

        fun from(image: Image): YuvPlanes? {
            val sampleBytes = when (image.format) {
                ImageFormat.YUV_420_888 -> 1
                ImageFormat.YCBCR_P010 -> 2
                else -> return null
            }
            val planes = image.planes
            if (planes.size < 3) return null
            val crop = image.cropRect
            return YuvPlanes(
                y = planes[0].buffer,
                u = planes[1].buffer,
                v = planes[2].buffer,
                yRowStride = planes[0].rowStride,
                yPixelStride = planes[0].pixelStride,
                uvRowStride = planes[1].rowStride,
                uvPixelStride = planes[1].pixelStride,
                sampleBytes = sampleBytes,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
            )
        }

        /** Legacy raw output: I420 (planar) or NV12 (semi-planar) only. */
        fun from(buffer: ByteBuffer, format: MediaFormat): YuvPlanes? {
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val stride = format.intOr(MediaFormat.KEY_STRIDE, width)
            val sliceHeight = format.intOr(MediaFormat.KEY_SLICE_HEIGHT, height).coerceAtLeast(height)
            val left = format.intOr("crop-left", 0)
            val top = format.intOr("crop-top", 0)
            val right = format.intOr("crop-right", width - 1)
            val bottom = format.intOr("crop-bottom", height - 1)
            val ySize = stride * sliceHeight
            val (u, v, uvRowStride, uvPixelStride) = when (format.intOr(MediaFormat.KEY_COLOR_FORMAT, 0)) {
                COLOR_FORMAT_YUV420_PLANAR -> {
                    val chroma = (stride / 2) * (sliceHeight / 2)
                    Quad(buffer.sliceAt(ySize), buffer.sliceAt(ySize + chroma), stride / 2, 1)
                }
                COLOR_FORMAT_YUV420_SEMI_PLANAR ->
                    Quad(buffer.sliceAt(ySize), buffer.sliceAt(ySize + 1), stride, 2)
                else -> return null
            }
            return YuvPlanes(buffer, u, v, stride, 1, uvRowStride, uvPixelStride, 1, left, top, right - left + 1, bottom - top + 1)
        }

        private data class Quad(val u: ByteBuffer, val v: ByteBuffer, val rowStride: Int, val pixelStride: Int)

        private fun ByteBuffer.sliceAt(offset: Int): ByteBuffer = duplicate().apply { position(offset) }.slice()

        private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default
    }
}
