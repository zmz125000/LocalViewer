package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.Collections
import java.util.IdentityHashMap
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
    private val busy = Collections.newSetFromMap(IdentityHashMap<ArchiveByteSource, Boolean>())
    private val dead = Collections.newSetFromMap(IdentityHashMap<ArchiveByteSource, Boolean>())
    private val lock = Any()

    /**
     * Close [source] and never hand it out again. An in-flight read on that
     * handle unblocks, so a photo-grid cell that left the sheet frees its slot.
     */
    internal fun retire(source: ArchiveByteSource) {
        synchronized(lock) {
            idle.remove(source)
            all.remove(source)
            if (source in busy) dead.add(source)
        }
        runCatching { source.close() }
    }

    @PublishedApi
    internal suspend fun <T> use(block: (ArchiveByteSource) -> T): T {
        gate.acquire()
        val source = try {
            synchronized(lock) {
                val taken = if (idle.isEmpty()) {
                    open().also { all += it }
                } else {
                    idle.removeFirst()
                }
                busy.add(taken)
                taken
            }
        } catch (e: Throwable) {
            gate.release()
            throw e
        }
        try {
            return block(source)
        } finally {
            val retired = synchronized(lock) {
                busy.remove(source)
                val retired = dead.remove(source)
                if (!retired) idle.addLast(source)
                retired
            }
            if (retired) runCatching { source.close() }
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
