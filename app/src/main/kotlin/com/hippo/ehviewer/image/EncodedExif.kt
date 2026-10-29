package com.hippo.ehviewer.image

import androidx.exifinterface.media.ExifInterface
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.jni.extractAvifExif
import com.hippo.ehviewer.jni.extractAvifExifInput
import com.hippo.ehviewer.jni.extractJxlExif
import com.hippo.ehviewer.jni.extractJxlExifInput
import com.hippo.ehviewer.jni.mapStillInput
import com.hippo.ehviewer.jni.unmapStillInput
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer

/** Shared by the metadata widget and exports; container extraction never decodes pixels. */
internal fun ImageSource.readEncodedExif(fileName: String?, header: ByteArray = byteArrayOf()): ExifInterface? {
    val extension = fileName?.substringAfterLast('.', "")?.lowercase()
    // A network cache can contain a converted JPEG under the original native filename.
    // Prefer the actual bytes whenever the caller already has a header.
    val jxl = if (header.isEmpty()) {
        extension == "jxl"
    } else {
        (header.size >= 2 && header[0] == 0xff.toByte() && header[1] == 0x0a.toByte()) ||
            (header.size >= 12 && header.copyOfRange(4, 12).contentEquals(byteArrayOf(74, 88, 76, 32, 13, 10, -121, 10)))
    }
    val avif = if (header.isEmpty()) {
        extension == "avif"
    } else {
        header.size >= 16 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            "avif" in String(header, 8, minOf(header.size - 8, 56), Charsets.US_ASCII)
    }
    if (jxl || avif) {
        val bytes = readContainerExif(jxl)
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

/** Reuse RAM or mmap the cached file. A pipe is streamed, never fully staged for metadata. */
private fun ImageSource.readContainerExif(jxl: Boolean): ByteArray? {
    fun direct(buffer: ByteBuffer) = if (jxl) extractJxlExif(buffer) else extractAvifExif(buffer)
    fun chunks(input: EncodedExifInput) = if (jxl) extractJxlExifInput(input) else extractAvifExifInput(input)
    return when (this) {
        is ByteBufferSource -> {
            val buffer = source.slice()
            if (buffer.isDirect) direct(buffer) else chunks(EncodedExifInput(buffer))
        }
        is PathSource -> {
            source.openFileDescriptor("r").use { descriptor ->
                val buffer = mapStillInput(descriptor.fd)
                if (buffer != null) {
                    return try {
                        direct(buffer)
                    } finally {
                        unmapStillInput(buffer)
                    }
                }
                // Use the same descriptor even for a pipe-backed document.
                android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                    chunks(EncodedExifInput(it))
                }
            }
        }
    }
}
