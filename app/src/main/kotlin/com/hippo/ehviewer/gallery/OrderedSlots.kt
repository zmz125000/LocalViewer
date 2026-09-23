package com.hippo.ehviewer.gallery

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Rank of a page that is not in the current demand list. */
internal const val NOT_IN_ORDER = Int.MAX_VALUE

/**
 * Viewport pages still extract when the demand list is not published yet.
 * Prefetch outside that list stays [NOT_IN_ORDER] and waits.
 */
internal fun pdfExtractOrderRank(prefetchRank: Int, interactive: Boolean): Int = when {
    prefetchRank != NOT_IN_ORDER -> prefetchRank
    interactive -> 0
    else -> NOT_IN_ORDER
}

/**
 * Position of [index] among pages in [ordered] that still need this stage.
 * 0 is the next page from the viewport. Ready / failed pages are skipped.
 */
internal fun orderedWorkRank(
    ordered: List<Int>,
    index: Int,
    skip: (Int) -> Boolean,
): Int {
    var rank = 0
    for (candidate in ordered) {
        if (skip(candidate)) continue
        if (candidate == index) return rank
        rank++
    }
    return NOT_IN_ORDER
}

/**
 * Run [block] only while [rank] is inside the slot window.
 *
 * Window width is one reserved slot plus [fallbackPermits]. Rank 0 waits on
 * [serialSlots]. The following ranks fill [fallbackSlots] in the same order.
 * A later page does not start until an earlier one finishes and its rank
 * moves into the window, so every slot downloads or decodes in demand order.
 *
 * Eligibility is re-checked after acquire so a seek can move the window
 * without holding a permit for work that is no longer next.
 */
internal suspend fun <T> withOrderedPermits(
    rank: () -> Int,
    serialSlots: Semaphore,
    fallbackSlots: Semaphore,
    fallbackPermits: Int,
    block: suspend () -> T,
): T {
    val extra = if (fallbackSlots === serialSlots) 0 else fallbackPermits.coerceAtLeast(0)
    val window = 1 + extra
    while (true) {
        val current = rank()
        when {
            current == 0 -> serialSlots.withPermit {
                if (rank() == 0) return block()
            }
            current in 1 until window -> fallbackSlots.withPermit {
                val now = rank()
                if (now in 1 until window) return block()
            }
            else -> delay(ORDER_POLL_MS)
        }
    }
}

private const val ORDER_POLL_MS = 16L
