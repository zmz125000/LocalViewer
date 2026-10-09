package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.byteBufferSource
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import okio.Path

/**
 * Pages already held as raw image bytes (MOBI / AZW3 direct image).
 * [notifySourceReady] runs immediately; there is no extract step.
 */
internal class RamBytesPageLoader(
    scope: CoroutineScope,
    titleHint: String,
    private val pagesBytes: List<Pair<ByteArray, String>>,
    startPage: Int,
) : PageLoader(
    scope,
    info = null,
    startPage = startPage,
    initialSize = pagesBytes.size,
) {
    override val title: String = titleHint

    override fun getImageExtension(index: Int): String? = pagesBytes.getOrNull(index)?.second

    override fun savePage(index: Int, file: Path): Boolean = runCatching {
        val (bytes, _) = pagesBytes.getOrNull(index) ?: return@runCatching false
        File(file.toString()).writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun openSource(index: Int): ImageSource {
        val (bytes, _) = pagesBytes[index]
        return byteBufferSource(ByteBuffer.wrap(bytes)) {}
    }

    override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
        notifySourceReady(index, orgImg)
    }
}
