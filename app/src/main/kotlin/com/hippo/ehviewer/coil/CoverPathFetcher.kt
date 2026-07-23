package com.hippo.ehviewer.coil

import android.content.ContentResolver
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
 * [CoverPath.path] is the stable cache identity; Android [android.net.Uri] is only
 * resolved when Coil actually needs to open the file (background).
 */
class CoverPathFetcher(
    private val data: CoverPath,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Path.toUri() may ContentResolver.query for mediastore: — safe here (fetcher thread).
        val androidUri = data.path.toPath().toUri()
        val contentResolver = options.context.contentResolver
        val afd = when (androidUri.scheme) {
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE ->
                contentResolver.openAssetFileDescriptor(androidUri, "r")
            ContentResolver.SCHEME_FILE ->
                contentResolver.openAssetFileDescriptor(androidUri, "r")
            else ->
                contentResolver.openAssetFileDescriptor(androidUri, "r")
        }
        checkNotNull(afd) { "Unable to open cover: ${data.path} → $androidUri" }

        val coilUri: CoilUri = androidUri.toString().toCoilUri()
        return SourceFetchResult(
            source = ImageSource(
                source = afd.createInputStream().source().buffer(),
                fileSystem = options.fileSystem,
                metadata = ContentMetadata(coilUri, afd),
            ),
            mimeType = contentResolver.getType(androidUri),
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
