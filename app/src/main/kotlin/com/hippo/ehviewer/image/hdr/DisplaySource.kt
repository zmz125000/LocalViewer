package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.util.FileUtils
import okio.Path

/**
 * Reader chokepoint: Coil / ImageDecoder-ready [ImageSource].
 *
 * Lib convert (JXR/JXL/PQ-AVIF → Ultra HDR JPEG) lives here and in network finalize —
 * not in [com.hippo.ehviewer.image.Image].
 *
 * Local archives pass a mmap [ByteBufferSource] ([extractToByteBuffer]). Platform
 * JPEG/PNG/… stay in that buffer (EhViewer direct access). Only lib stills become a
 * converted JPEG [PathSource].
 */
object DisplaySource {
    /**
     * @return Coil-ready source. Platform [ByteBufferSource] is unchanged (no disk).
     *         Caller must [ImageSource.close] the returned source (closes the original when wrapped).
     */
    suspend fun ensureReady(src: ImageSource, fileNameHint: String = "page.bin"): ImageSource = when (src) {
        is PathSource -> ensureReadyPath(src)
        is ByteBufferSource -> ensureReadyBuffer(src, fileNameHint)
    }

    private suspend fun ensureReadyPath(src: PathSource): PathSource {
        // Prefer real filename for HEIC/ProXDR sniff; SAF document ids sometimes lack an ext.
        val hint = src.source.name.let { name ->
            if (name.contains('.')) {
                name
            } else {
                val t = src.type.trimStart('.')
                if (t.isNotEmpty()) "$name.$t" else name
            }
        }
        val ready = HdrConvertCache.ensureCoilReady(src.source, hint)
        if (ready.toString() == src.source.toString()) return src
        val outer = src
        return object : PathSource {
            override val source: Path = ready
            override val type: String =
                FileUtils.getExtensionFromFilename(ready.name) ?: "jpg"
            override fun close() = outer.close()
        }
    }

    private suspend fun ensureReadyBuffer(src: ByteBufferSource, fileNameHint: String): ImageSource {
        val route = classify(src.source, fileNameHint)
        if (!route.needsUhdr) return src
        val dup = src.source.asReadOnlyBuffer()
        val n = dup.remaining()
        check(n > 0) { "empty image buffer" }
        val bytes = ByteArray(n)
        dup.get(bytes)
        val ready = HdrConvertCache.ensureCoilReadyFromBytes(bytes, fileNameHint)
        val outer = src
        return object : PathSource {
            override val source: Path = ready
            override val type: String =
                FileUtils.getExtensionFromFilename(ready.name) ?: "jpg"
            override fun close() = outer.close()
        }
    }
}
