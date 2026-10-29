package com.hippo.ehviewer.image

import androidx.annotation.Keep
import java.io.InputStream
import java.nio.ByteBuffer

/** JNI metadata-only input. Never copies the complete compressed image or changes its buffer position. */
@Keep
internal class EncodedExifInput private constructor(
    private val buffer: ByteBuffer?,
    private val stream: InputStream?,
) {
    constructor(buffer: ByteBuffer) : this(buffer.asReadOnlyBuffer(), null)
    constructor(stream: InputStream) : this(null, stream)

    // A pipe cannot seek backwards. Retain only its initial container metadata;
    // later image payload is skipped/discarded rather than staged to a file.
    private val prefix = if (stream != null) ByteArray(MAX_READ) else null
    private var position = 0L
    private var recentStart = 0L
    private var recent = byteArrayOf()

    @Suppress("unused") // Called by JNI; @Keep protects the method name in release builds.
    fun readAt(offset: Long, size: Int): ByteArray? {
        if (offset < 0 || size !in 0..MAX_READ) return null
        buffer?.let {
            if (offset > it.remaining()) return null
            val count = minOf(size.toLong(), it.remaining() - offset).toInt()
            return ByteArray(count).also { bytes ->
                it.duplicate().apply { position(position() + offset.toInt()) }.get(bytes)
            }
        }
        val input = checkNotNull(stream)
        val cached = checkNotNull(prefix)
        while (position < offset) {
            if (position < cached.size) {
                val count = minOf(offset - position, cached.size - position).toInt()
                val read = input.read(cached, position.toInt(), count)
                if (read <= 0) return null
                position += read
            } else {
                val skipped = input.skip(offset - position)
                if (skipped > 0) {
                    position += skipped
                } else {
                    if (input.read() < 0) return null
                    position++
                }
            }
        }
        val result = ByteArray(size)
        var count = 0
        while (count < size) {
            val at = offset + count
            if (at < position) {
                val fromPrefix = at < minOf(position, cached.size.toLong())
                val source = if (fromPrefix) cached else recent
                val start = if (fromPrefix) at else at - recentStart
                val available = if (fromPrefix) minOf(position, cached.size.toLong()) - at else source.size - start
                if (start < 0 || available <= 0) return null
                val copied = minOf(size - count, available.toInt())
                source.copyInto(result, count, start.toInt(), start.toInt() + copied)
                count += copied
            } else {
                val read = input.read(result, count, size - count)
                if (read <= 0) break
                if (position < cached.size) {
                    val copied = minOf(read, cached.size - position.toInt())
                    result.copyInto(cached, position.toInt(), count, count + copied)
                }
                position += read
                count += read
            }
        }
        recentStart = offset
        recent = if (count == size) result else result.copyOf(count)
        return recent
    }

    private companion object {
        const val MAX_READ = 1024 * 1024
    }
}
