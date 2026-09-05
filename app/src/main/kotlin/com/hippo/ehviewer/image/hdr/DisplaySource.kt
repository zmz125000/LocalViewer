package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.files.read
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.util.FileUtils
import java.nio.ByteBuffer
import kotlinx.io.readByteArray
import okio.Path

/**
 * Reader chokepoint: Coil / ImageDecoder-ready [ImageSource].
 *
 * Lib convert (JXR/JXL/PQ-AVIF → Ultra HDR JPEG) lives here and in network finalize —
 * not in [com.hippo.ehviewer.image.Image]. ImageDecoder cannot open those codecs
 * (`Failed to create image decoder` / `unimplemented`); cache-off must still convert.
 *
 * Local archives pass a mmap [ByteBufferSource] ([extractToByteBuffer]). Platform
 * JPEG/PNG/… stay in that buffer (EhViewer direct access). Lib stills become a
 * converted JPEG [PathSource]. When [persistTo] is set (network cache-off), that
 * JPEG is the Ultra HDR **sibling of the page-cache key** so scroll-back hits
 * disk instead of re-fetching. Otherwise it lands under the derived Ultra HDR
 * cache (content hash / local path).
 */
object DisplaySource {
    /**
     * @param persistTo network/extract page-cache primary (e.g. `hash.jxl`). Lib
     *        convert writes the `.jpg` sibling [HdrConvertCache.uhdrSiblingOf]
     *        so loaders can [HdrConvertCache.resolvePagePath] on the next request.
     * @return Coil-ready source. Platform [ByteBufferSource] is unchanged (no disk).
     *         Caller must [ImageSource.close] the returned source (closes the original when wrapped).
     */
    suspend fun ensureReady(
        src: ImageSource,
        fileNameHint: String = "page.bin",
        persistTo: Path? = null,
    ): ImageSource = when (src) {
        is PathSource -> ensureReadyPath(src, persistTo)
        is ByteBufferSource -> ensureReadyBuffer(src, fileNameHint, persistTo)
    }

    private suspend fun ensureReadyPath(src: PathSource, persistTo: Path?): PathSource {
        // Prefer real filename for HEIC/ProXDR sniff; SAF document ids sometimes lack an ext.
        val hint = src.source.name.let { name ->
            if (name.contains('.')) {
                name
            } else {
                val t = src.type.trimStart('.')
                if (t.isNotEmpty()) "$name.$t" else name
            }
        }
        val ready = convertLibToCoilReady(src.source, hint, persistTo)
        if (ready.toString() == src.source.toString()) return src
        val outer = src
        return object : PathSource {
            override val source: Path = ready
            override val type: String =
                FileUtils.getExtensionFromFilename(ready.name) ?: "jpg"
            override fun close() = outer.close()
        }
    }

    private suspend fun ensureReadyBuffer(
        src: ByteBufferSource,
        fileNameHint: String,
        persistTo: Path?,
    ): ImageSource {
        val route = classify(src.source, fileNameHint)
        if (!route.needsUhdr) return src
        val bytes = src.source.heapBytesForConvert()
        check(bytes.isNotEmpty()) { "empty image buffer" }
        val ready = convertLibBytes(bytes, fileNameHint, persistTo)
        src.close()
        return object : PathSource {
            override val source: Path = ready
            override val type: String =
                FileUtils.getExtensionFromFilename(ready.name) ?: "jpg"
            override fun close() = Unit
        }
    }

    private suspend fun convertLibToCoilReady(source: Path, hint: String, persistTo: Path?): Path {
        if (persistTo == null) return HdrConvertCache.ensureCoilReady(source, hint)
        val route = classifyPath(source, hint)
        if (!route.needsUhdr) return source
        val bytes = source.read { readByteArray() }
        check(bytes.isNotEmpty()) { "empty image file: $hint" }
        return convertLibBytes(bytes, hint, persistTo)
    }

    private suspend fun convertLibBytes(bytes: ByteArray, fileNameHint: String, persistTo: Path?): Path = if (persistTo != null) {
        HdrConvertCache.finalizeNetworkBytes(bytes, persistTo, fileNameHint)
    } else {
        HdrConvertCache.ensureCoilReadyFromBytes(bytes, fileNameHint)
    }
}

/**
 * ramPages wrap a heap [ByteArray] — reuse it for lib convert instead of cloning
 * a 20–30 MiB page. Direct/mmap buffers still copy.
 */
internal fun ByteBuffer.heapBytesForConvert(): ByteArray {
    if (hasArray() && !isReadOnly) {
        val n = remaining()
        if (arrayOffset() == 0 && position() == 0 && n == array().size) return array()
    }
    val dup = asReadOnlyBuffer()
    val n = dup.remaining()
    if (n <= 0) return ByteArray(0)
    return ByteArray(n).also { dup.get(it) }
}
