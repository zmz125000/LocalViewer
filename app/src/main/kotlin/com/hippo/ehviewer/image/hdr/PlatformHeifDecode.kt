package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.ehviewer.core.files.read
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.tryHardwareF16Wrap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** SDR AVIF/HEIF uses Android's optimized still decoder; no JPEG conversion or pixel loop. */
internal object PlatformHeifDecode {
    suspend fun precision(src: ImageSource, hint: String): HeifPrecision? = withContext(Dispatchers.IO) {
        val ext = hint.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext !in setOf("avif", "heic", "heif", "heics", "heifs", "hif", "bin", "tmp")) return@withContext null
        runCatching {
            when (src) {
                is ByteBufferSource -> {
                    val buffer = src.source.asReadOnlyBuffer()
                    val bytes = ByteArray(minOf(buffer.remaining(), HeifPrecisionProbe.HEADER_BYTES))
                    buffer.get(bytes)
                    HeifPrecisionProbe.probe(bytes)
                }
                is PathSource -> src.source.read {
                    val bytes = ByteArray(HeifPrecisionProbe.HEADER_BYTES)
                    val n = readAtMostTo(bytes)
                    if (n > 0) HeifPrecisionProbe.probe(bytes, n) else null
                }
            }
        }.getOrNull()
    }

    private val hevcP010: Boolean by lazy {
        Build.VERSION.SDK_INT >= 33 && runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                    codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
                    )
            }
        }.getOrDefault(false)
    }

    suspend fun decode(src: ImageSource, info: HeifPrecision, maxEdge: Int): LibDirectResult? = withContext(Dispatchers.IO) {
        // A F16 destination alone cannot recover precision from an 8-bit HEVC
        // codec output. Use software HEVC when the platform lacks readable P010.
        if (heifDecodePath(info, advancedColor = true, hevcP010 = hevcP010) != HeifDecodePath.PlatformDeep) return@withContext null
        LibDirectDecode.withHeavyDecode {
            runCatching {
                val source = when (src) {
                    is ByteBufferSource -> ImageDecoder.createSource(src.source.asReadOnlyBuffer())
                    is PathSource -> ImageDecoder.createSource(appCtx.contentResolver, src.source.toUri())
                }
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, image, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                    val edge = maxOf(image.size.width, image.size.height)
                    if (maxEdge > 0 && edge > maxEdge) {
                        val scale = maxEdge.toDouble() / edge
                        decoder.setTargetSize(
                            (image.size.width * scale).roundToInt().coerceAtLeast(1),
                            (image.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
                if (bitmap.config != Bitmap.Config.RGBA_F16) {
                    bitmap.recycle()
                    return@withHeavyDecode null
                }
                Log.i("ReaderColor", "platform AVIF/HEIF F16 ${bitmap.width}x${bitmap.height} sourceDepth=${info.bitDepth}")
                LibDirectResult(
                    bitmap = tryHardwareF16Wrap(bitmap) ?: bitmap,
                    isHdrContent = false,
                    contentHdrBoost = 1f,
                    isWideGamutSource = true,
                )
            }.onFailure { Log.w("ReaderColor", "Platform high-depth decode failed; using native codec", it) }.getOrNull()
        }
    }
}
