package com.hippo.ehviewer.gallery

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Extra concurrent RAM downloads besides the reserved anchor lane.
 *
 * Cache-off holds compressed bytes on the Java heap. Matching browse-thumb
 * width (1 reserved + 2) keeps peak RAM near the current stable path instead
 * of opening the full SMB pool (tens of full files).
 */
@PublishedApi
internal const val RAM_PREFETCH_PERMITS = 2

/**
 * Run a folder-network page copy on the right semaphore.
 *
 * The viewport anchor tries the reserved interactive lane so a seek does not
 * sit behind mate / decode-ahead / source-only work. It never *waits* on that
 * lane: if the previous page still holds it, the copy falls through to the
 * bounded prefetch/RAM lane and starts immediately.
 *
 * Cache-off always uses [ramPrefetchSlots] for that fallback (not the full
 * pool). Cache-on lib-HDR/AVIF convert uses [libHdrPrefetchSlots].
 */
@PublishedApi
internal suspend fun <T> withFolderNetworkPermit(
    isAnchor: Boolean,
    cacheOff: Boolean,
    libHdr: Boolean,
    interactiveSlots: Semaphore,
    ramPrefetchSlots: Semaphore,
    libHdrPrefetchSlots: Semaphore,
    prefetchSlots: Semaphore,
    block: suspend () -> T,
): T {
    if (isAnchor && interactiveSlots.tryAcquire()) {
        try {
            return block()
        } finally {
            interactiveSlots.release()
        }
    }
    val fallback = when {
        cacheOff -> ramPrefetchSlots
        libHdr -> libHdrPrefetchSlots
        else -> prefetchSlots
    }
    return fallback.withPermit { block() }
}
