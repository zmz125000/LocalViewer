package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.library.ArchiveByteSource
import kotlinx.coroutines.sync.Semaphore

/**
 * Parallel page-body reads. Indexing keeps the parser source; these are extra.
 *
 * One SMB file serializes every read on a single worker, so each slot opens
 * its own handle. Two slots on the index handle do not overlap.
 */
@PublishedApi
internal const val PDF_EXTRACT_SLOTS = 2

/**
 * A few independent [ArchiveByteSource]s so listed PDF pages can be extracted
 * while the page tree is still walked on the primary source.
 */
@PublishedApi
internal class PdfExtractPool(
    private val open: () -> ArchiveByteSource,
    slots: Int = PDF_EXTRACT_SLOTS,
) {
    private val gate = Semaphore(slots)
    private val idle = ArrayDeque<ArchiveByteSource>()
    private val all = ArrayList<ArchiveByteSource>()
    private val lock = Any()

    @PublishedApi
    internal suspend fun <T> use(block: (ArchiveByteSource) -> T): T {
        gate.acquire()
        val source = try {
            synchronized(lock) {
                if (idle.isEmpty()) open().also { all += it } else idle.removeFirst()
            }
        } catch (e: Throwable) {
            gate.release()
            throw e
        }
        try {
            return block(source)
        } finally {
            synchronized(lock) { idle.addLast(source) }
            gate.release()
        }
    }

    @PublishedApi
    internal fun close() {
        val sources = synchronized(lock) {
            val snapshot = all.toList()
            all.clear()
            idle.clear()
            snapshot
        }
        sources.forEach { runCatching { it.close() } }
    }
}
