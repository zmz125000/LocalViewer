package com.hippo.ehviewer.image.hdr

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import splitties.init.appCtx

/** Keep small downloads fast; spill large compressed originals before array growth/copying. */
internal suspend fun <T> withBufferedImageDownload(
    download: suspend (OutputStream) -> Unit,
    fromBytes: suspend (ByteArray) -> T,
    fromFile: suspend (File) -> T,
): T {
    var memory: ByteArrayOutputStream? = ByteArrayOutputStream(256 * 1024)
    var spill: File? = null
    var disk: OutputStream? = null
    fun target(length: Int): OutputStream {
        val buffer = memory
        if (buffer != null && length > 1024 * 1024 - buffer.size()) {
            val file = File.createTempFile("image-download", ".bin", appCtx.cacheDir)
            spill = file
            val stream = file.outputStream()
            disk = stream
            buffer.writeTo(stream)
            memory = null
        }
        return disk ?: checkNotNull(memory)
    }
    val output = object : OutputStream() {
        override fun write(value: Int) = target(1).write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = target(length).write(bytes, offset, length)
        override fun flush() {
            disk?.flush()
        }
    }
    try {
        download(output)
        disk?.close()
        disk = null
        val file = spill
        return if (file != null) fromFile(file) else fromBytes(checkNotNull(memory).toByteArray())
    } finally {
        try {
            disk?.close()
        } finally {
            spill?.delete()
        }
    }
}
