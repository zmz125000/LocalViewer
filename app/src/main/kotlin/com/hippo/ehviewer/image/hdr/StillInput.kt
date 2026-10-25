package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.mapStillInput
import com.hippo.ehviewer.jni.unmapStillInput
import java.io.File
import java.nio.ByteBuffer
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/** Synchronous borrowed input. The callback must not retain the buffer or any views. */
internal fun <T> withStillInput(src: ImageSource, work: (ByteBuffer) -> T): T? = when (src) {
    is ByteBufferSource -> work(src.source.slice())
    is PathSource -> withStillInput(src.source, work)
}

internal fun <T> withStillInput(path: Path, work: (ByteBuffer) -> T): T? {
    val opened = runCatching { path.openFileDescriptor("r") }.getOrNull()
    opened?.use { descriptor ->
        val buffer = mapStillInput(descriptor.fd)
        if (buffer != null) {
            return try {
                work(buffer)
            } finally {
                unmapStillInput(buffer)
            }
        }
    }
    // Pipe-backed SAF documents cannot be mapped. Copy with a small fixed buffer.
    val staged = File.createTempFile("still-input", ".bin", appCtx.cacheDir)
    try {
        appCtx.contentResolver.openInputStream(path.toUri()).use { input ->
            checkNotNull(input) { "Unreadable still source: $path" }
            staged.outputStream().use { input.copyTo(it) }
        }
        staged.toOkioPath().openFileDescriptor("r").use { descriptor ->
            val buffer = mapStillInput(descriptor.fd) ?: return null
            return try {
                work(buffer)
            } finally {
                unmapStillInput(buffer)
            }
        }
    } finally {
        staged.delete()
    }
}

/** Small stream adapter for platform fallbacks; never materializes a compressed array. */
internal class StillBufferInputStream(private val buffer: ByteBuffer) : java.io.InputStream() {
    override fun read(): Int = if (buffer.hasRemaining()) buffer.get().toInt() and 0xff else -1

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return 0
        if (!buffer.hasRemaining()) return -1
        val count = minOf(length, buffer.remaining())
        buffer.get(bytes, offset, count)
        return count
    }
}
