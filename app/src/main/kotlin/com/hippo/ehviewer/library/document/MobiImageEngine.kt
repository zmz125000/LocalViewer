package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import java.util.concurrent.ConcurrentHashMap
import okio.Path

/**
 * Image-only MOBI / AZW / AZW3. Every image-magic record is a page (a novel's
 * cover and illustrations, or a comic's pages). Text is not reflowed.
 */
class MobiImageEngine private constructor(
    private val source: ArchiveByteSource,
    private val pages: List<MobiText.ImagePage>,
    private val remoteSize: Long,
    knownExts: Map<Int, String>,
) : DocumentImageEngine {
    /** Extensions learned from a payload or an earlier index, for pages listed without magic. */
    private val sniffed = ConcurrentHashMap(knownExts)

    override val pageCount: Int get() = pages.size

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext ?: sniffed[index]

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = FORMAT,
        // A complete index names page files by extension. An unknown one cannot be found.
        complete = complete && pages.indices.all { extOf(it) != null },
        members = pages.mapIndexed { i, page ->
            DocumentExtractCache.Member(
                i = i,
                name = "mobi:${i + 1}",
                ext = extOf(i) ?: UNKNOWN_EXT,
                uncSize = page.length.toLong(),
            )
        },
    )

    override fun extractToCache(cacheKey: String, index: Int): Path? {
        val page = pages.getOrNull(index) ?: return null
        val known = extOf(index)
        if (known != null) {
            if (DocumentExtractCache.isPageCached(cacheKey, index, known)) {
                return DocumentExtractCache.pagePath(cacheKey, index, known)
            }
        } else {
            DocumentExtractCache.findCachedPage(cacheKey, index)?.let { cached ->
                val ext = cached.name.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext != UNKNOWN_EXT) {
                    sniffed[index] = ext
                    return cached
                }
            }
        }
        val bytes = readExact(page) ?: return null
        val ext = known ?: MobiText.imageExt(bytes)?.also { sniffed[index] = it } ?: return null
        return DocumentExtractCache.writePage(cacheKey, index, ext, bytes)
    }

    override fun extractBytes(index: Int): ByteArray? {
        val page = pages.getOrNull(index) ?: return null
        val bytes = readExact(page) ?: return null
        if (extOf(index) == null) MobiText.imageExt(bytes)?.let { sniffed[index] = it }
        return bytes
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
        private const val UNKNOWN_EXT = "bin"

        /**
         * @return engine, or null when the container cannot be read.
         * An empty [pageCount] means the book has no image records.
         * Image bytes are read in [extractToCache], not here.
         * [cachedIndex] supplies extensions of pages extracted in an earlier session.
         */
        fun open(
            source: ArchiveByteSource,
            remoteSize: Long = 0L,
            cachedIndex: DocumentExtractCache.Index? = null,
        ): MobiImageEngine? {
            val pages = MobiText.imagePages(source) ?: return null
            val known = HashMap<Int, String>()
            if (cachedIndex?.format == FORMAT && cachedIndex.members.size == pages.size) {
                for (m in cachedIndex.members) {
                    val page = pages.getOrNull(m.i) ?: continue
                    if (page.ext == null && m.ext != UNKNOWN_EXT && m.uncSize == page.length.toLong()) {
                        known[m.i] = m.ext
                    }
                }
            }
            return MobiImageEngine(source, pages, remoteSize, known)
        }
    }
}
