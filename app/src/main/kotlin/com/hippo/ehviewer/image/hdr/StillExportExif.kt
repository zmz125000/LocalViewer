package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.jni.extractJxlExif
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import okio.Path
import splitties.init.appCtx

/** Portable photographic EXIF; structural TIFF/RAW offsets and maker notes are not transplantable. */
internal class StillExportExif private constructor(private val attributes: Map<String, String>) {
    fun encode(width: Int, height: Int): ByteArray? = try {
        val file = File.createTempFile("still-exif", ".jpg", appCtx.cacheDir)
        try {
            file.writeBytes(template)
            ExifInterface(file).apply {
                attributes.forEach { (tag, value) -> setAttribute(tag, value) }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
                // The output encoder supplies its own ICC/CICP; never copy source colorimetry.
                setAttribute(ExifInterface.TAG_COLOR_SPACE, "65535")
                saveAttributes()
            }
            file.inputStream().buffered().use { stream ->
                check(stream.read() == 0xff && stream.read() == 0xd8)
                while (true) {
                    check(stream.read() == 0xff)
                    var marker = stream.read()
                    while (marker == 0xff) marker = stream.read()
                    if (marker < 0 || marker == 0xda || marker == 0xd9) break
                    val size = (stream.read() shl 8) or stream.read()
                    check(size >= 2)
                    val bytes = stream.readExact(size - 2)
                    check(bytes.size == size - 2)
                    if (marker == 0xe1 && bytes.size >= 6 && bytes.copyOfRange(0, 6).contentEquals(exifPrefix)) {
                        return@use bytes.copyOfRange(6, bytes.size)
                    }
                }
                null
            }
        } finally {
            file.delete()
        }
    } catch (error: Exception) {
        Log.w("StillConvert", "Cannot serialize EXIF", error)
        null
    }

    companion object {
        private fun InputStream.readExact(size: Int): ByteArray = ByteArray(size).also { DataInputStream(this).readFully(it) }
        private val exifPrefix = byteArrayOf(69, 120, 105, 102, 0, 0)
        private val template by lazy {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }

        // Preserve photography, attribution and location, excluding image-storage and color tags.
        private val photoTags = setOf(
            "Make", "Model", "Software", "Artist", "Copyright", "ImageDescription", "UserComment",
            "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal",
            "OffsetTimeDigitized", "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
            "ExposureTime", "FNumber", "ExposureProgram", "ISOSpeedRatings", "PhotographicSensitivity",
            "SensitivityType", "StandardOutputSensitivity", "RecommendedExposureIndex", "ISOSpeed",
            "ShutterSpeedValue", "ApertureValue", "BrightnessValue", "ExposureBiasValue", "MaxApertureValue",
            "SubjectDistance", "MeteringMode", "LightSource", "Flash", "FocalLength", "FocalLengthIn35mmFilm",
            "FlashEnergy", "ExposureIndex", "SensingMethod", "FileSource", "SceneType", "CustomRendered",
            "ExposureMode", "WhiteBalance", "DigitalZoomRatio", "SceneCaptureType", "GainControl", "Contrast",
            "Saturation", "Sharpness", "SubjectDistanceRange", "ImageUniqueID", "CameraOwnerName",
            "BodySerialNumber", "LensSpecification", "LensMake", "LensModel", "LensSerialNumber",
        )

        // Explicit constants survive R8 inlining/shrinking. Reflecting TAG_* fields
        // produces an empty list in release builds because those fields are removed.
        private val tags = photoTags + setOf(
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )

        fun read(source: Path, name: String): StillExportExif? {
            return try {
                val extension = name.substringAfterLast('.', "").lowercase()
                val exif = if (extension == "jxl") {
                    val bytes = withStillInput(source) { extractJxlExif(it) } ?: return null
                    // JPEG XL Exif boxes start with a big-endian offset to the TIFF header.
                    if (bytes.size < 4) return null
                    val offset = java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
                    if (offset > bytes.size - 4L) return null
                    ExifInterface(
                        ByteArrayInputStream(exifPrefix + bytes.copyOfRange(4 + offset.toInt(), bytes.size)),
                        ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY,
                    )
                } else if (extension in setOf("jxr", "wdp", "hdp")) {
                    ParcelFileDescriptor.AutoCloseInputStream(source.openFileDescriptor("r")).use { input ->
                        PushbackInputStream(input, 4).use { stream ->
                            val prefix = stream.readExact(4)
                            // JXR is TIFF-like; its EXIF IFD pointers use the same offsets.
                            if (prefix.contentEquals(byteArrayOf(73, 73, 0xbc.toByte(), 1))) {
                                prefix[2] = 42
                                prefix[3] = 0
                            }
                            stream.unread(prefix)
                            ExifInterface(stream)
                        }
                    }
                } else {
                    // Keep seekable local/SAF files on the descriptor path rather
                    // than buffering a large camera image through an InputStream.
                    source.openFileDescriptor("r").use { ExifInterface(it.fileDescriptor) }
                }
                var remaining = 24_000
                val values = buildMap {
                    for (tag in tags) {
                        val value = exif.getAttribute(tag) ?: continue
                        val bytes = value.toByteArray().size
                        if (bytes > remaining) continue
                        remaining -= bytes
                        put(tag, value)
                    }
                }
                Log.i("StillConvert", "EXIF read: $name fields=${values.size}")
                values.takeIf { it.isNotEmpty() }?.let(::StillExportExif)
            } catch (error: Exception) {
                Log.w("StillConvert", "Cannot read EXIF from $name", error)
                null
            }
        }
    }
}
