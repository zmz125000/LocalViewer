package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import okio.Path

/**
 * Image-only MOBI / AZW / AZW3. Every image-magic record is a page (a novel's
 * cover and illustrations, or a comic's pages). Text is not reflowed.
 */
class MobiImageEngine private constructor(
    private val source: ArchiveByteSource,
    private val pages: List<MobiText.ImagePage>,
    private val remoteSize: Long,
) : DocumentImageEngine {
    override val pageCount: Int get() = pages.size

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = FORMAT,
        complete = complete,
        members = pages.mapIndexed { i, page ->
            DocumentExtractCache.Member(
                i = i,
                name = "mobi:${i + 1}",
                ext = page.ext,
                uncSize = page.length.toLong(),
            )
        },
    )

    override fun extractToCache(cacheKey: String, index: Int): Path? {
        val page = pages.getOrNull(index) ?: return null
        if (DocumentExtractCache.isPageCached(cacheKey, index, page.ext)) {
            return DocumentExtractCache.pagePath(cacheKey, index, page.ext)
        }
        val bytes = readExact(page) ?: return null
        return DocumentExtractCache.writePage(cacheKey, index, page.ext, bytes)
    }

    private fun readExact(page: MobiText.ImagePage): ByteArray? {
        if (page.length <= 0) return null
        val buf = ByteArray(page.length)
        var got = 0
        while (got < page.length) {
            val n = source.readAt(page.offset + got, buf, got, page.length - got)
            if (n <= 0) return null
            got += n
        }
        return buf
    }

    companion object {
        const val FORMAT = "mobi"

        /**
         * @return engine, or null when the container cannot be read.
         * An empty [pageCount] means the book has no image records.
         * Image bytes are read in [extractToCache], not here.
         */
        fun open(source: ArchiveByteSource, remoteSize: Long = 0L): MobiImageEngine? {
            val pages = MobiText.imagePages(source) ?: return null
            return MobiImageEngine(source, pages, remoteSize)
        }
    }
}
