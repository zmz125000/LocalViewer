package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.jni.convertSourceEncodedStill
import com.hippo.ehviewer.jni.encodeSourceRgb
import java.io.File
import java.nio.ByteBuffer
import okio.Path

internal data class SourceEncodedResult(val file: File, val target: StillExportTarget, val encoding: String? = null)

/** Regular-format exports keep the decoded source encoding, independent of reader/display settings. */
internal suspend fun sourceEncodedExport(source: Path, name: String, target: StillExportTarget, quality: StillExportQuality): SourceEncodedResult? {
    val format = when (target) {
        StillExportTarget.Jpeg -> 0
        StillExportTarget.Jxl -> 1
        StillExportTarget.Avif -> 2
        else -> return null // Explicit RAW/HDR rendering still uses the selected conversion controls.
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    val kind = when (ext) {
        "jpg", "jpeg", "jpe", "jfif" -> 0
        "avif" -> 1
        "jxl" -> 4
        "heic", "heif", "heics", "heifs", "hif" -> 2
        "png", "webp", "gif", "bmp", "ico", "wbmp" -> 3
        else -> return null
    }
    if (format == 0 && kind == 4) return null // JXL has no standard gain-map container to retain.
    val out = File.createTempFile("source-out", null, splitties.init.appCtx.cacheDir)
    var success = false
    try {
        val info = IntArray(5)
        val code = withStillInput(source) { input ->
            if (kind == 3) {
                encodePlatformSource(source, name, input, format, out, quality, info)
            } else if (kind != 2) {
                convertSourceEncodedStill(
                    input, if (kind == 4) 3 else kind, format, out.absolutePath, quality.value, quality.jxlDistance,
                    quality.codecQuality, quality.avifBitDepth, quality.downsizeGainMap, quality.keepExif, info,
                )
            } else {
                encodeHeifSource(source, name, input, format, out, quality, info)
            }
        } ?: 0
        check(code >= 0) { "Source-encoded conversion failed: $name -> $target rc=$code" }
        if (code == 0) return null
        check(out.length() > 0) { "Source-encoded conversion produced no output: $name" }
        success = true
        val actual = when {
            info[2] != 0 && format == 0 -> StillExportTarget.JpegHdr
            info[2] != 0 && format == 2 -> StillExportTarget.AvifGainMap
            info[3] == 16 && format == 1 -> StillExportTarget.JxlPq
            info[3] == 16 && format == 2 -> StillExportTarget.AvifPq
            info[3] == 8 && info[4] > 255 && format == 1 -> StillExportTarget.JxlHdr
            else -> target
        }
        android.util.Log.i("StillConvert", "Kept source color: $name -> $actual transfer=${info[3]} gainmap=${info[2] != 0}")
        return SourceEncodedResult(out, actual, if (info[3] == 18) "hlg" else null)
    } finally {
        if (!success) out.delete()
    }
}

private fun simpleGainMetadata(maximum: Float, display: Float = maximum) = floatArrayOf(
    1f, 1f, 1f, maximum, maximum, maximum, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, display,
)

private fun encodeHeifSource(source: Path, name: String, input: ByteBuffer, format: Int, out: File, quality: StillExportQuality, info: IntArray): Int {
    if (format == 0 && !OppoProxdr.looksLike(input) && HeifContainer.parse(input)?.hasAppleGainMap() != true) return 0
    val frame = HevcHdrDecode.decode(input, 0, advancedColor = true, allowHdr = true, encodedRgb = true) ?: return -96
    frame.use {
        val color = checkNotNull(frame.sourceEncoding)
        val exif = if (quality.keepExif) StillExportExif.read(source, name)?.encode(frame.width, frame.height) else null
        var ownedGain: NativeStillBuffer? = null
        try {
            var gain: ByteBuffer? = null
            var gainW = 0
            var gainH = 0
            var jpeg = false
            var metadata: FloatArray? = null
            if (format != 1) {
                val oppo = OppoProxdr.parse(input)
                if (oppo != null) {
                    gain = oppo.maskJpeg.slice()
                    gainW = oppo.maskWidth
                    gainH = oppo.maskHeight
                    jpeg = true
                    metadata = simpleGainMetadata(oppo.maxContentBoost, oppo.displayRatioForFullHdr)
                } else {
                    val apple = frame.sourceContainer?.appleGainMap()
                    if (apple != null) {
                        val bitmap = checkNotNull(AppleGainMapDecode.decode(apple)) { "Cannot decode source HEIF gain map" }
                        try {
                            gainW = bitmap.width
                            gainH = bitmap.height
                            ownedGain = NativeStillBuffer.allocate(gainW.toLong() * gainH * 4)
                            gain = ownedGain.buffer
                            bitmap.copyPixelsToBuffer(gain)
                            gain.clear()
                            metadata = simpleGainMetadata(apple.headroom)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            val bits = color.tiles.maxOf { it.bitDepth }
            return encodeSourceRgb(
                frame.pixels.buffer, frame.width, frame.height, bits,
                if (color.icc != null && !color.hasNclx) 2 else color.primaries,
                if (color.icc != null && !color.hasNclx) 2 else color.transfer,
                color.icc, 0, format, out.absolutePath, quality.value, quality.jxlDistance, quality.codecQuality,
                quality.avifBitDepth, quality.downsizeGainMap, exif, gain, gainW, gainH, jpeg, metadata, info,
            )
        } finally {
            ownedGain?.close()
        }
    }
}

/** Extract profiles without materializing the compressed image. */
private fun sourceIcc(bytes: ByteBuffer, extension: String): ByteArray? {
    val input = bytes.asReadOnlyBuffer().order(java.nio.ByteOrder.BIG_ENDIAN)
    if (extension == "png" && input.remaining() >= 8) {
        input.position(input.position() + 8)
        while (input.remaining() >= 12) {
            val size = input.int.toLong() and 0xffffffffL
            val type = input.int
            if (size > input.remaining() - 4L) return null
            if (type == 0x69434350 && size <= 1024 * 1024) { // iCCP
                val chunk = input.slice().apply { limit(size.toInt()) }
                while (chunk.hasRemaining() && chunk.get().toInt() != 0) { /* Skip the profile name. */ }
                if (!chunk.hasRemaining() || chunk.get().toInt() != 0) return null
                return java.util.zip.InflaterInputStream(StillBufferInputStream(chunk)).use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= 1024 * 1024) {
                        val n = stream.read(buffer)
                        if (n < 0) return@use out.toByteArray()
                        out.write(buffer, 0, n)
                    }
                    error("Source ICC exceeds metadata limit")
                }
            }
            input.position(input.position() + size.toInt() + 4)
        }
    } else if (extension == "webp" && input.remaining() >= 12) {
        input.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        input.position(input.position() + 12)
        while (input.remaining() >= 8) {
            val type = input.int
            val size = input.int.toLong() and 0xffffffffL
            if (size > input.remaining()) return null
            if (type == 0x50434349 && size <= 1024 * 1024) return ByteArray(size.toInt()).also { input.get(it) }
            val padded = size + (size and 1)
            if (padded > input.remaining()) return null
            input.position(input.position() + padded.toInt())
        }
    }
    return null
}

/** PNG cICP is needed even when Android reports an SDR fallback profile. */
private fun pngColorTags(bytes: ByteBuffer): Pair<Int?, Int>? {
    val input = bytes.asReadOnlyBuffer().order(java.nio.ByteOrder.BIG_ENDIAN)
    if (input.remaining() < 8) return null
    input.position(input.position() + 8)
    var srgb = false
    var gamma: Int? = null
    while (input.remaining() >= 12) {
        val size = input.int.toLong() and 0xffffffffL
        val type = input.int
        if (size > input.remaining() - 4L) return null
        if (type == 0x63494350 && size == 4L) {
            val p = input.get(input.position()).toInt() and 255
            val t = input.get(input.position() + 1).toInt() and 255
            if (input.get(input.position() + 2).toInt() == 0 && input.get(input.position() + 3).toInt() == 1) return p to t
        }
        if (type == 0x73524742) srgb = true
        if (type == 0x67414d41 && size == 4L) gamma = input.getInt(input.position())
        if (type == 0x49444154) break
        input.position(input.position() + size.toInt() + 4)
    }
    if (srgb) return 1 to 13
    return when (gamma) {
        100000 -> null to 8
        45455 -> null to 4
        35714 -> null to 5
        else -> null
    }
}

private fun encodePlatformSource(source: Path, name: String, input: ByteBuffer, format: Int, out: File, quality: StillExportQuality, info: IntArray): Int {
    if (format == 0) return 0
    val ext = name.substringAfterLast('.', "").lowercase()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(StillBufferInputStream(input.slice()), null, bounds)
    val icc = sourceIcc(input, ext)
    val space = bounds.outColorSpace ?: if (icc != null && ext == "png") ColorSpace.get(ColorSpace.Named.SRGB) else return 0
    val prefix = ByteArray(minOf(input.remaining(), 64)).also { input.duplicate().get(it) }
    val depth = if (PlatformBitDepth.probe(prefix, fileNameHint = name) == BitDepthClass.HIGH) 16 else 8
    val orientation = runCatching {
        source.openFileDescriptor("r").use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrDefault(1).coerceIn(1, 8)
    val primaries = when {
        icc != null -> 2
        space == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> 12
        space == ColorSpace.get(ColorSpace.Named.BT2020) || space == ColorSpace.get(ColorSpace.Named.BT2020_PQ) || space == ColorSpace.get(ColorSpace.Named.BT2020_HLG) -> 9
        !space.isWideGamut -> 1
        else -> return 0
    }
    val pngTags = if (ext == "png") pngColorTags(input) else null
    val encodedPrimaries = pngTags?.first ?: primaries
    val params = (space as? ColorSpace.Rgb)?.transferParameters
    val transfer = when {
        pngTags != null && (icc == null || pngTags.second == 16 || pngTags.second == 18) -> pngTags.second
        icc != null -> 2
        space == ColorSpace.get(ColorSpace.Named.BT2020_PQ) -> 16
        space == ColorSpace.get(ColorSpace.Named.BT2020_HLG) -> 18
        space.isSrgb || space == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> 13
        params?.g == 1.0 -> 8
        params != null && kotlin.math.abs(params.g - 2.2) < 0.001 -> 4
        params != null && kotlin.math.abs(params.g - 2.8) < 0.001 -> 5
        else -> return 0
    }
    if (ext == "png") {
        val exif = if (quality.keepExif) StillExportExif.read(source, name)?.encode(bounds.outWidth, bounds.outHeight) else null
        pngSourcePixels(input, bounds.outWidth, bounds.outHeight)?.use { pixels ->
            return encodeSourceRgb(
                pixels.buffer, bounds.outWidth, bounds.outHeight, depth, encodedPrimaries, transfer, icc,
                orientation shl 8, format, out.absolutePath, quality.value, quality.jxlDistance, quality.codecQuality,
                quality.avifBitDepth, quality.downsizeGainMap, exif, null, 0, 0, false, null, info,
            )
        }
    }
    val bitmap = BitmapFactory.decodeStream(
        StillBufferInputStream(input.slice()),
        null,
        BitmapFactory.Options().apply {
            inScaled = false
            inPremultiplied = false
            inPreferredConfig = if (depth > 8) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
            inPreferredColorSpace = space
        },
    ) ?: return -97
    try {
        check(bitmap.colorSpace == space) { "Platform decoder changed source color encoding: $name" }
        val f16 = bitmap.config == Bitmap.Config.RGBA_F16
        if (depth > 8) check(f16) { "Platform decoder lost source depth: $name" }
        check(bitmap.config == Bitmap.Config.ARGB_8888 || f16) { "Unsupported source RGB storage: ${bitmap.config}" }
        val exif = if (quality.keepExif) StillExportExif.read(source, name)?.encode(bitmap.width, bitmap.height) else null
        NativeStillBuffer.allocate(bitmap.width.toLong() * bitmap.height * if (f16) 8 else 4).use { pixels ->
            bitmap.copyPixelsToBuffer(pixels.buffer)
            return encodeSourceRgb(
                pixels.buffer, bitmap.width, bitmap.height, depth, encodedPrimaries, transfer, icc,
                (if (f16) 2 else 1) or (if (bitmap.isPremultiplied) 4 else 0) or (orientation shl 8), format, out.absolutePath,
                quality.value, quality.jxlDistance, quality.codecQuality, quality.avifBitDepth, quality.downsizeGainMap,
                exif, null, 0, 0, false, null, info,
            )
        }
    } finally {
        bitmap.recycle()
    }
}
