package com.hippo.ehviewer.image

import androidx.exifinterface.media.ExifInterface
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.image.hdr.withStillInput
import com.hippo.ehviewer.jni.extractAvifExif
import com.hippo.ehviewer.jni.extractJxlExif
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer

/** Shared by the metadata widget and exports; container extraction never decodes pixels. */
internal fun ImageSource.readEncodedExif(fileName: String?, header: ByteArray = byteArrayOf()): ExifInterface? {
    val extension = fileName?.substringAfterLast('.', "")?.lowercase()
    val jxl = extension == "jxl" || (
        header.size >= 12 &&
            header.copyOfRange(4, 12).contentEquals(byteArrayOf(74, 88, 76, 32, 13, 10, -121, 10))
        )
    val avif = extension == "avif" || (
        header.size >= 16 &&
            String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            "avif" in String(header, 8, minOf(header.size - 8, 56), Charsets.US_ASCII)
        )
    if (jxl || avif) {
        val bytes = withStillInput(this) { if (jxl) extractJxlExif(it) else extractAvifExif(it) }
        if (bytes != null) {
            // JXL boxes include their TIFF offset; libavif returns the TIFF payload.
            val offset = if (jxl) {
                if (bytes.size < 4) return null
                val value = ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
                if (value > bytes.size - 4L) return null
                4 + value.toInt()
            } else {
                0
            }
            return ExifInterface(
                ByteArrayInputStream(byteArrayOf(69, 120, 105, 102, 0, 0) + bytes.copyOfRange(offset, bytes.size)),
                ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY,
            )
        }
        if (jxl) return null
        // Retain the platform fallback for AVIF containers libavif cannot parse.
    }
    if (extension in setOf("jxr", "wdp", "hdp") ||
        (header.size >= 4 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(73, 73, -68, 1)))
    ) {
        return metadataStream().use { input ->
            PushbackInputStream(input, 4).use { stream ->
                val prefix = ByteArray(4).also { DataInputStream(stream).readFully(it) }
                // JXR's photographic IFDs have TIFF-compatible offsets.
                if (prefix.contentEquals(byteArrayOf(73, 73, -68, 1))) {
                    prefix[2] = 42
                    prefix[3] = 0
                }
                stream.unread(prefix)
                ExifInterface(stream)
            }
        }
    }
    return when (this) {
        is PathSource -> source.openFileDescriptor("r").use { ExifInterface(it.fileDescriptor) }
        is ByteBufferSource -> metadataStream().use { ExifInterface(it) }
    }
}
