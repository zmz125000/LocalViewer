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

/**
 * A document whose playable page list is discovered incrementally.
 *
 * Network PDF uses this to publish the first page without walking every page/resource
 * object. Callers may extend the list just ahead of the reader and persist the partial
 * seek index between sessions.
 */
interface ProgressiveDocumentImageEngine : DocumentImageEngine {
    /** True only after the page tree has reached a verified end. */
    val structureComplete: Boolean

    /**
     * Discover playable images through [index], or until the page tree ends.
     * Returns the current number of discovered playable pages.
     */
    fun ensureListedThrough(index: Int): Int
}
