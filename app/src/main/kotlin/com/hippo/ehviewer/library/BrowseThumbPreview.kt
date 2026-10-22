package com.hippo.ehviewer.library

import com.hippo.ehviewer.coil.CoverPath
import com.hippo.ehviewer.image.hdr.classify
import com.hippo.ehviewer.image.hdr.classifyPath
import com.hippo.ehviewer.image.hdr.needsLibDecode
import kotlinx.coroutines.CompletableDeferred
import okio.Path

/** Source published to Coil before the optional thumbnail encode starts. */
class BrowseThumbPreview(val data: Any) {
    val decoded = CompletableDeferred<Unit>()
}

internal suspend fun previewBrowseThumb(
    bytes: ByteArray,
    fileName: String,
    publish: (suspend (BrowseThumbPreview) -> Unit)?,
) {
    if (publish == null || classify(bytes, fileNameHint = fileName).needsLibDecode) return
    val preview = BrowseThumbPreview(bytes)
    publish(preview)
    preview.decoded.await()
}

internal suspend fun previewBrowseThumb(
    path: Path,
    publish: (suspend (BrowseThumbPreview) -> Unit)?,
) {
    if (publish == null || classifyPath(path).needsLibDecode) return
    val preview = BrowseThumbPreview(CoverPath(path.toString()))
    publish(preview)
    preview.decoded.await()
}
