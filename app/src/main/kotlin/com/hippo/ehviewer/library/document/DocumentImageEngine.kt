package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.DocumentExtractCache
import okio.Path

/**
 * Image-only document extract engine (EPUB / PDF).
 * Index + page files into [DocumentExtractCache]; no UI / navigation.
 */
interface DocumentImageEngine {
    val pageCount: Int
    fun extOf(index: Int): String?
    fun extractToCache(cacheKey: String, index: Int): Path?
    fun toIndex(cacheKey: String, complete: Boolean = true): DocumentExtractCache.Index
}
