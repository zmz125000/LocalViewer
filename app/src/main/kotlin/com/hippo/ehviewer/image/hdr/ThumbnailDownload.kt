package com.hippo.ehviewer.image.hdr

import java.io.File
import java.io.OutputStream
import okio.Path.Companion.toOkioPath

/** Small network thumbs stay in RAM; large originals spill before growing/copying arrays. */
internal suspend fun writeDownloadedThumbnail(
    dest: File,
    edge: Int,
    quality: Int,
    name: String,
    download: suspend (OutputStream) -> Unit,
): Boolean = withBufferedImageDownload(
    download = download,
    fromBytes = { HdrConvertCache.writeThumbFromBytes(it, dest, edge, quality, name) },
    fromFile = { HdrConvertCache.writeThumb(it.toOkioPath(), dest, edge, quality, name) },
)
