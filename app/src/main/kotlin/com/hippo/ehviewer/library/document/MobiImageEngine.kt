package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import okio.Path

/**
 * Image-only MOBI / AZW / AZW3. Every image-magic record is a page (a novel's
 * cover and illustrations, or a comic's pages). Text is not reflowed.
 */
class MobiImageEngine private constructor(
    private val pages: List<Pair<ByteArray, String>>,
    private val remoteSize: Long,
) : DocumentImageEngine {
    override val pageCount: Int get() = pages.size

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.second

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = FORMAT,
        complete = complete,
        members = pages.mapIndexed { i, (bytes, ext) ->
            DocumentExtractCache.Member(
                i = i,
                name = "mobi:${i + 1}",
                ext = ext,
                uncSize = bytes.size.toLong(),
            )
        },
    )

    override fun extractToCache(cacheKey: String, index: Int): Path? {
        val (bytes, ext) = pages.getOrNull(index) ?: return null
        if (DocumentExtractCache.isPageCached(cacheKey, index, ext)) {
            return DocumentExtractCache.pagePath(cacheKey, index, ext)
        }
        return DocumentExtractCache.writePage(cacheKey, index, ext, bytes)
    }

    companion object {
        const val FORMAT = "mobi"

        /**
         * @return engine, or null when the container cannot be read.
         * An empty [pageCount] means the book has no image records.
         */
        fun open(source: ArchiveByteSource, remoteSize: Long = 0L): MobiImageEngine? {
            val bytes = source.readFully(MobiText.MAX_IMAGE_BYTES) ?: return null
            val pages = MobiText.imageBlobs(bytes).mapNotNull { chunk ->
                val ext = MobiText.imageExt(chunk) ?: return@mapNotNull null
                chunk to ext
            }
            return MobiImageEngine(pages, remoteSize)
        }
    }
}
