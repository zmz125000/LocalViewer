package com.hippo.ehviewer.image

import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.image.hdr.HeifPrecisionProbe
import com.hippo.ehviewer.image.hdr.isRawStillExtension
import com.hippo.ehviewer.jni.probeStillExportSize
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import splitties.init.appCtx

enum class ImageTransfer {
    PQ,
    HLG,
    LINEAR,
    SRGB,
    GAIN_MAP,
    ;

    companion object {
        internal fun fromCicp(value: Int?): ImageTransfer? = when (value) {
            16 -> PQ
            18 -> HLG
            8 -> LINEAR
            13 -> SRGB
            else -> null
        }

        internal fun fromColorSpace(colorSpace: ColorSpace?): ImageTransfer? = (colorSpace as? ColorSpace.Rgb)?.transferParameters?.let {
            if (it.g == 1.0 && it.a == 1.0 && it.b == 0.0 && it.e == 0.0 && it.f == 0.0 && (it.d == 0.0 || it.c == 1.0)) {
                LINEAR
            } else {
                null
            }
        }
    }
}

/** The framework has no display string for a transfer curve; retain its actual coefficients. */
internal fun ColorSpace?.rawTransferDescription(): String? = (this as? ColorSpace.Rgb)?.transferParameters?.let {
    "a=${it.a}, b=${it.b}, c=${it.c}, d=${it.d}, e=${it.e}, f=${it.f}, g=${it.g}"
}

/** Original encoded metadata; color tags may fall back to the decoded bitmap when unavailable. */
data class ImageMetadata(
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val colorSpace: String? = null,
    val hdr: Boolean? = null,
    val transfer: ImageTransfer? = null,
    val rawTransfer: String? = null,
    val date: Long? = null,
    val cameraModel: String? = null,
    val exposureSeconds: Double? = null,
    val aperture: Double? = null,
    val focalLength35mm: Int? = null,
    val focalLength: Double? = null,
    val iso: Int? = null,
    val lensModel: String? = null,
)

/** Header/EXIF reads only: never decodes pixels or retains the compressed source. */
internal fun ImageSource.readMetadata(fileDate: Long? = null, fileName: String? = null): ImageMetadata {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { metadataStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
    val header = runCatching {
        metadataStream().use { input ->
            val bytes = ByteArray(HeifPrecisionProbe.HEADER_BYTES)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            bytes.copyOf(count)
        }
    }.getOrDefault(byteArrayOf())
    val exif = runCatching { readEncodedExif(fileName, header) }.getOrNull()
    val heif = HeifPrecisionProbe.probe(header)
    val dimensions = HeifPrecisionProbe.thumbnailMemory(ByteBuffer.wrap(header))?.dimensions ?: 0L
    val extension = fileName?.substringAfterLast('.', "")?.lowercase()
    val kind = when {
        extension == "jxl" -> 0
        extension == "jxr" || extension == "wdp" || extension == "hdp" -> 1
        isRawStillExtension(extension) -> 2
        else -> -1
    }
    val nativeDimensions = if (kind >= 0 && bounds.outWidth <= 0) {
        runCatching { nativeDimensions(kind, header) }.getOrDefault(0L)
    } else {
        0L
    }
    fun attributeInt(tag: String) = exif?.getAttributeInt(tag, 0)?.takeIf { it > 0 }
    fun attributeDouble(tag: String) = exif?.getAttributeDouble(tag, Double.NaN)?.takeIf { it.isFinite() && it > 0 }
    val depth = encodedBitDepth(header) ?: heif?.bitDepth ?: exif?.getAttribute(ExifInterface.TAG_BITS_PER_SAMPLE)
        ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.maxOrNull()
    val headerText = header.toString(Charsets.ISO_8859_1)
    val gainmapMarker = listOf("hdrgm:", "HDRGainMap", "GainMap", "urn:com:apple:photo:2020:aux:hdrgainmap").firstOrNull { it in headerText }
    val gainmap = gainmapMarker != null
    return ImageMetadata(
        width = bounds.outWidth.takeIf { it > 0 } ?: (nativeDimensions ushr 32).toInt().takeIf { it > 0 }
            ?: attributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION) ?: attributeInt(ExifInterface.TAG_IMAGE_WIDTH)
            ?: (dimensions ushr 32).toInt().takeIf { it > 0 },
        height = bounds.outHeight.takeIf { it > 0 } ?: nativeDimensions.toInt().takeIf { it > 0 }
            ?: attributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION) ?: attributeInt(ExifInterface.TAG_IMAGE_LENGTH)
            ?: dimensions.toInt().takeIf { it > 0 },
        bitDepth = depth,
        // CICP primaries describe the source gamut independently of its transfer curve.
        // BitmapFactory can report sRGB here for a BT.2020 AVIF with an sRGB transfer.
        colorSpace = when (heif?.primaries) {
            1 -> if (heif.transfer == 13) "sRGB" else "BT.709"
            9 -> "BT.2020"
            11 -> "DCI-P3"
            12 -> "Display P3"
            else -> bounds.outColorSpace?.name ?: if (attributeInt(ExifInterface.TAG_COLOR_SPACE) == 1) "sRGB" else null
        },
        hdr = when {
            gainmap || heif?.transfer in listOf(16, 18) -> true
            heif?.transfer != null || bounds.outWidth > 0 -> false
            else -> null
        },
        transfer = if (gainmap) {
            ImageTransfer.GAIN_MAP
        } else {
            ImageTransfer.fromCicp(heif?.transfer)
                ?: ImageTransfer.fromColorSpace(bounds.outColorSpace)
        },
        rawTransfer = gainmapMarker ?: heif?.transfer?.let { "CICP transfer $it" }
            ?: bounds.outColorSpace.rawTransferDescription(),
        date = exif?.photoDate() ?: fileDate?.takeIf { it > 0 },
        cameraModel = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
        lensModel = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
        exposureSeconds = attributeDouble(ExifInterface.TAG_EXPOSURE_TIME),
        aperture = attributeDouble(ExifInterface.TAG_F_NUMBER),
        focalLength35mm = attributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
        focalLength = attributeDouble(ExifInterface.TAG_FOCAL_LENGTH),
        iso = attributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeUnless { it == 65535 }
            ?: attributeInt(ExifInterface.TAG_ISO_SPEED),
    )
}

private fun ImageSource.nativeDimensions(kind: Int, header: ByteArray): Long = when (this) {
    is PathSource -> source.openFileDescriptor("r").use { probeStillExportSize("/proc/self/fd/${it.fd}", kind) }
    is ByteBufferSource -> {
        // The same bounded native header probe used for thumbnail admission.
        val file = File.createTempFile("reader-metadata", ".bin", appCtx.cacheDir)
        try {
            file.writeBytes(header)
            probeStillExportSize(file.absolutePath, kind)
        } finally {
            file.delete()
        }
    }
}

internal fun ImageSource.metadataStream(): InputStream = when (this) {
    is PathSource -> ParcelFileDescriptor.AutoCloseInputStream(source.openFileDescriptor("r"))
    is ByteBufferSource -> {
        val buffer = source.asReadOnlyBuffer()
        object : InputStream() {
            override fun read(): Int = if (buffer.hasRemaining()) buffer.get().toInt() and 255 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (!buffer.hasRemaining()) return -1
                val count = minOf(length, buffer.remaining())
                buffer.get(bytes, offset, count)
                return count
            }
            override fun skip(count: Long): Long {
                val skipped = count.coerceIn(0L, buffer.remaining().toLong()).toInt()
                buffer.position(buffer.position() + skipped)
                return skipped.toLong()
            }
            override fun available(): Int = buffer.remaining()
        }
    }
}.buffered()

private fun ExifInterface.photoDate(): Long? {
    val tags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL to ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED to ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_DATETIME to ExifInterface.TAG_OFFSET_TIME,
    )
    for ((dateTag, offsetTag) in tags) {
        val value = getAttribute(dateTag) ?: continue
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            isLenient = false
            getAttribute(offsetTag)?.takeIf { it.matches(Regex("[+-]\\d{2}:\\d{2}")) }?.let {
                timeZone = TimeZone.getTimeZone("GMT$it")
            }
        }
        runCatching { format.parse(value)?.time }.getOrNull()?.let { return it }
    }
    return null
}

/** Encoded component depth, never the decoder's output bitmap storage depth. */
internal fun encodedBitDepth(bytes: ByteArray): Int? {
    fun u8(at: Int) = bytes[at].toInt() and 255
    if (bytes.size >= 25 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) &&
        String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR"
    ) {
        return u8(24).takeIf { it in 1..16 }
    }
    if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    ) {
        return 8
    }
    if (bytes.size < 2 || u8(0) != 255 || u8(1) != 216) return null
    var at = 2
    while (at + 1 < bytes.size) {
        if (u8(at++) != 255) return null
        while (at < bytes.size && u8(at) == 255) at++
        if (at >= bytes.size) return null
        val marker = u8(at++)
        if (marker == 218 || marker == 217) return null
        if (marker == 1 || marker in 208..215) continue
        if (at + 2 > bytes.size) return null
        val size = (u8(at) shl 8) or u8(at + 1)
        if (size < 2 || size > bytes.size - at) return null
        if (marker in 192..207 && marker !in listOf(196, 200, 204)) {
            if (size < 3) return null
            return u8(at + 2).takeIf { it in 1..16 }
        }
        at += size
    }
    return null
}
