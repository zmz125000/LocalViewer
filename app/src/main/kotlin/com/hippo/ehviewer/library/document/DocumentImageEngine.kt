package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.DocumentExtractCache
import okio.Path

/**
 * Image-only document extract engine (EPUB / PDF).
 * Index + page files into [DocumentExtractCache]; no UI / navigation.
 *
 * [AutoCloseable] so [com.hippo.ehviewer.gallery.useDocumentExtractPageLoader] can
 * register the engine with [moe.tarsin.kt.install] and release resources when the
 * reader session ends (same pattern as stream sources).
 */
interface DocumentImageEngine : AutoCloseable {
    val pageCount: Int
    fun extOf(index: Int): String?
    fun extractToCache(cacheKey: String, index: Int): Path?
    fun toIndex(cacheKey: String, complete: Boolean = true): DocumentExtractCache.Index

    override fun close() = Unit
}
