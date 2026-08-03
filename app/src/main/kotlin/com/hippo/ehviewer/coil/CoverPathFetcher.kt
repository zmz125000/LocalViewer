package com.hippo.ehviewer.coil

import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.asImage
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import coil3.toUri as toCoilUri
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.image.hdr.HdrKind
import com.hippo.ehviewer.image.hdr.isHdrConvertCandidateExtension
import com.hippo.ehviewer.image.hdr.sniffHdrPath
import com.hippo.ehviewer.util.FileUtils
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.source

/**
 * Local cover path model for Coil. Keeps heavy URI resolution ([Path.toUri] MediaStore
 * lookup) off the main/composition thread — resolution happens inside [fetch].
 */
data class CoverPath(val path: String)

/**
 * Fetches cover bytes for SAF / file / MediaStore virtual paths.
 *
 * - **HDR** lib (JXR/JXL/PQ-AVIF): [HdrConvertCache.ensureCoverSource] → Ultra HDR JPEG
 * - **SDR** lib (JXR/JXL): lib decode → Bitmap (no UHDR jpg cache)
 * - Platform formats: open original
 */
class CoverPathFetcher(
    private val data: CoverPath,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val path = data.path.toPath()
        val ext = FileUtils.getExtensionFromFilename(path.name)?.lowercase()
        if (isHdrConvertCandidateExtension(ext)) {
            val sniff = sniffHdrPath(path)
            if (sniff.needsUhdrConvert) {
                return openAsSource(HdrConvertCache.ensureCoverSource(path))
            }
            if (sniff.kind == HdrKind.JpegXr || sniff.kind == HdrKind.JpegXl) {
                val edge = coverDecodeEdge(options)
                val bmp = HdrConvertCache.decodeLibSdrBitmap(path, path.name, edge)
                    ?: error("Lib SDR decode failed: ${path.name}")
                return ImageFetchResult(
                    image = bmp.asImage(),
                    isSampled = edge > 0,
                    dataSource = DataSource.DISK,
                )
            }
        }
        return openAsSource(path)
    }

    private fun openAsSource(openPath: Path): SourceFetchResult {
        val androidUri = openPath.toUri()
        val contentResolver = options.context.contentResolver
        val afd = contentResolver.openAssetFileDescriptor(androidUri, "r")
        checkNotNull(afd) { "Unable to open cover: ${data.path} → $androidUri" }
        val coilUri: CoilUri = androidUri.toString().toCoilUri()
        return SourceFetchResult(
            source = ImageSource(
                source = afd.createInputStream().source().buffer(),
                fileSystem = options.fileSystem,
                metadata = ContentMetadata(coilUri, afd),
            ),
            mimeType = contentResolver.getType(androidUri) ?: "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<CoverPath> {
        override fun create(data: CoverPath, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverPathFetcher(data, options)
    }
}

private fun coverDecodeEdge(options: Options): Int {
    val w = (options.size.width as? Dimension.Pixels)?.px ?: 0
    val h = (options.size.height as? Dimension.Pixels)?.px ?: 0
    return maxOf(w, h).takeIf { it > 0 } ?: 512
}

/** Memory/disk keyer so CoverPath caches without relying on URI identity. */
object CoverPathKeyer : Keyer<CoverPath> {
    override fun key(data: CoverPath, options: Options): String = data.path
}
