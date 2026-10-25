package com.hippo.ehviewer.image.hdr

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/** Small network thumbs stay in RAM; large originals spill before growing/copying arrays. */
internal suspend fun writeDownloadedThumbnail(
    dest: File,
    edge: Int,
    quality: Int,
    name: String,
    download: suspend (OutputStream) -> Unit,
): Boolean {
    val buffer = ByteArrayOutputStream(256 * 1024)
    var spill: File? = null
    var disk: OutputStream? = null
    val output = object : OutputStream() {
        override fun write(value: Int) {
            val one = byteArrayOf(value.toByte())
            write(one, 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (disk == null && length > 1024 * 1024 - buffer.size()) {
                val file = File.createTempFile("thumb-source", ".bin", appCtx.cacheDir)
                spill = file
                val stream = file.outputStream()
                disk = stream
                buffer.writeTo(stream)
                buffer.reset()
            }
            (disk ?: buffer).write(bytes, offset, length)
        }

        override fun flush() {
            disk?.flush()
        }
    }
    try {
        download(output)
        disk?.close()
        disk = null
        val source = spill
        return if (source != null) {
            HdrConvertCache.writeThumb(source.toOkioPath(), dest, edge, quality, name)
        } else {
            HdrConvertCache.writeThumbFromBytes(buffer.toByteArray(), dest, edge, quality, name)
        }
    } finally {
        try {
            disk?.close()
        } finally {
            spill?.delete()
        }
    }
}
