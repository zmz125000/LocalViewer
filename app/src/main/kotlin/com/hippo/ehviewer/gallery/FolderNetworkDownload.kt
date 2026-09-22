package com.hippo.ehviewer.gallery

import kotlinx.coroutines.sync.Semaphore

/**
 * Extra concurrent RAM downloads besides the reserved serial lane.
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
 * One serial slot walks source pages from the viewport in demand order and
 * waits for that slot (it does not fall through into the pool). Mate /
 * decode-ahead / source-only copies keep using [ramPrefetchSlots],
 * [libHdrPrefetchSlots], or [prefetchSlots] as before.
 *
 * Cache-off always uses [ramPrefetchSlots] for that remaining work (not the
 * full pool). Cache-on lib-HDR/AVIF convert uses [libHdrPrefetchSlots].
 */
@PublishedApi
internal suspend fun <T> withFolderNetworkPermit(
    isSerial: () -> Boolean,
    cacheOff: Boolean,
    libHdr: Boolean,
    serialSlots: Semaphore,
    ramPrefetchSlots: Semaphore,
    libHdrPrefetchSlots: Semaphore,
    prefetchSlots: Semaphore,
    block: suspend () -> T,
): T {
    val fallback = when {
        cacheOff -> ramPrefetchSlots
        libHdr -> libHdrPrefetchSlots
        else -> prefetchSlots
    }
    return withSerialOrFallbackPermit(
        isSerial = isSerial,
        serialSlots = serialSlots,
        fallbackSlots = fallback,
        block = block,
    )
}
