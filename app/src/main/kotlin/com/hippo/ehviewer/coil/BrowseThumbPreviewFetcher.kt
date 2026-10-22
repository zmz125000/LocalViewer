package com.hippo.ehviewer.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.hippo.ehviewer.library.BrowseThumbPreview
import okio.Buffer

/** The request keeps its preview model even after its bytes are replaced by a disk path. */
class BrowseThumbPreviewFetcher(
    private val preview: BrowseThumbPreview,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult = when (val source = preview.data) {
        is ByteArray -> SourceFetchResult(
            source = ImageSource(Buffer().write(source), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.MEMORY,
        )
        is CoverPath -> CoverPathFetcher(source, options).fetch()
        else -> error("Unsupported thumbnail preview source")
    }

    class Factory : Fetcher.Factory<BrowseThumbPreview> {
        override fun create(data: BrowseThumbPreview, options: Options, imageLoader: ImageLoader): Fetcher = BrowseThumbPreviewFetcher(data, options)
    }
}
