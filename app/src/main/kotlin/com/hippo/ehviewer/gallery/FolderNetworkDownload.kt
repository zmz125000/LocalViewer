package com.hippo.ehviewer.gallery

import kotlinx.coroutines.sync.Semaphore

/**
 * Extra concurrent RAM downloads besides the reserved head slot.
 *
 * Cache-off holds compressed bytes on the Java heap. Matching browse-thumb
 * width (1 reserved + 2) keeps peak RAM near the current stable path instead
 * of opening the full SMB pool (tens of full files).
 */
@PublishedApi
internal const val RAM_PREFETCH_PERMITS = 2

/** Cache-on lib-HDR / AVIF convert downloads, besides the reserved head slot. */
@PublishedApi
internal const val LIB_HDR_PREFETCH_PERMITS = 2

/**
 * Run a folder-network page copy on the lane that matches this page, but only
 * when its [rank] is inside that lane's ordered window.
 *
 * Rank 0 (next page from the viewport) waits on [serialSlots]. The following
 * ranks fill [ramPrefetchSlots], [libHdrPrefetchSlots], or [prefetchSlots]
 * in the same order. Pages past the window wait. Nothing falls through into
 * a free later slot.
 *
 * Cache-off uses [ramPrefetchSlots] (not the full pool). Cache-on lib-HDR/AVIF
 * convert uses [libHdrPrefetchSlots]. [prefetchPermitCount] is the size of
 * [prefetchSlots] when that lane is separate from [serialSlots].
 */
@PublishedApi
internal suspend fun <T> withFolderNetworkPermit(
    rank: () -> Int,
    cacheOff: Boolean,
    libHdr: Boolean,
    serialSlots: Semaphore,
    ramPrefetchSlots: Semaphore,
    libHdrPrefetchSlots: Semaphore,
    prefetchSlots: Semaphore,
    prefetchPermitCount: Int,
    block: suspend () -> T,
): T {
    val (fallback, extra) = when {
        cacheOff -> ramPrefetchSlots to RAM_PREFETCH_PERMITS
        libHdr -> libHdrPrefetchSlots to LIB_HDR_PREFETCH_PERMITS
        else -> prefetchSlots to prefetchPermitCount
    }
    return withOrderedPermits(
        rank = rank,
        serialSlots = serialSlots,
        fallbackSlots = fallback,
        fallbackPermits = extra,
        block = block,
    )
}
