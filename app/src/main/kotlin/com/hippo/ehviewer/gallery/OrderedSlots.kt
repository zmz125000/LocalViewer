package com.hippo.ehviewer.gallery

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield

/**
 * First page in viewport-order [ordered] that still needs this stage.
 * Ready / failed pages are skipped so the reserved slot walks forward in order.
 */
internal fun serialWorkHead(ordered: List<Int>, skip: (Int) -> Boolean): Int? = ordered.firstOrNull { !skip(it) }

/**
 * Run [block] on one reserved serial slot when [isSerial] is true, otherwise on
 * [fallbackSlots] (the remaining randomly-fired permits).
 *
 * The serial slot waits rather than stealing a fallback permit, so viewport-order
 * work cannot be delayed by decode-ahead / source-only jobs. Eligibility is
 * re-checked after acquire so a seek can move the head without deadlock.
 */
@PublishedApi
internal suspend inline fun <T> withSerialOrFallbackPermit(
    isSerial: () -> Boolean,
    serialSlots: Semaphore,
    fallbackSlots: Semaphore,
    crossinline block: suspend () -> T,
): T {
    if (fallbackSlots === serialSlots) {
        return serialSlots.withPermit { block() }
    }
    while (true) {
        if (isSerial()) {
            serialSlots.withPermit {
                if (isSerial()) {
                    return block()
                }
            }
        } else {
            fallbackSlots.withPermit {
                if (!isSerial()) {
                    return block()
                }
            }
        }
        yield()
    }
}
