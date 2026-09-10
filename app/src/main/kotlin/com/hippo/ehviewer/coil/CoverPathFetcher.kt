package com.hippo.ehviewer.coil

import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.toUri as toCoilUri
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.image.hdr.isHdrConvertCandidateExtension
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileNotFoundException
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
 * Lib formats → [HdrConvertCache.ensureCoilReady] (Ultra HDR JPEG); platform → original.
 * Zip-as-dir members extract first, then the **same** convert — ImageDecoder cannot open
 * JXL/JXR (`Failed to create image decoder` / `unimplemented`).
 */
class CoverPathFetcher(
    private val data: CoverPath,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val resolved = resolveFile()
        val hint = coverConvertHint(data.path, resolved.name)
        val ext = FileUtils.getExtensionFromFilename(hint)?.lowercase()
            ?: FileUtils.getExtensionFromFilename(resolved.name)?.lowercase()
        val openPath = if (isHdrConvertCandidateExtension(ext)) {
            HdrConvertCache.ensureCoilReady(resolved, hint)
        } else {
            resolved
        }
        return openAsSource(openPath)
    }

    private fun resolveFile(): Path {
        ZipPaths.parse(data.path)?.let { (zip, member) ->
            if (!isImageFileName(member)) {
                throw FileNotFoundException("ZIP member is not a cover: ${data.path}")
            }
            return ZipMemberCover.ensureLocal(zip, member, notifyTooLarge = false)
                ?: throw FileNotFoundException("ZIP cover missing: ${data.path}")
        }
        val path = data.path.toPath()
        // Absolute FS covers (archive_thumb, origin files): fail before openAFD if gone
        // (cache trim / clear data) so we don't spam ParcelFileDescriptor ENOENT.
        if (data.path.startsWith('/')) {
            val f = File(data.path)
            if (!f.isFile || f.length() <= 0L) {
                throw FileNotFoundException("Cover missing: ${data.path}")
            }
        }
        return path
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
        override fun create(data: CoverPath, options: Options, imageLoader: ImageLoader): Fetcher = CoverPathFetcher(data, options)
    }
}

/** Memory/disk keyer so CoverPath caches without relying on URI identity. */
object CoverPathKeyer : Keyer<CoverPath> {
    override fun key(data: CoverPath, options: Options): String = data.path
}

/**
 * Convert classify uses the zip member leaf (`a.jxl`), not the hashed extract name.
 * ImageDecoder cannot open those codecs — same rule as folder covers / [DisplaySource].
 */
internal fun coverConvertHint(coverPath: String, resolvedFileName: String): String = ZipPaths.memberLeafName(coverPath) ?: resolvedFileName
