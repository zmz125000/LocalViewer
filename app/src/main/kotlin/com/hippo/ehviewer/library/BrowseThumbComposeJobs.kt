package com.hippo.ehviewer.library

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many Compose [androidx.compose.runtime.LaunchedEffect] cells may run
 * heavy thumb work at once (browse video/image/archive covers, library/history
 * archive page-0 extract).
 *
 * Disk / memory cache probes stay outside [withJob] so a full grid of hits does not
 * serialize. Scroll-away cancels waiters and frees a permit.
 *
 * Slot count matches the usual fetch/decode concurrency (3) × 2 so a tablet grid
 * of ~50 cells cannot stampede IO/Binder/MediaCodec before those inner caps apply.
 */
object BrowseThumbComposeJobs {
    private const val FETCH_SLOTS = 3
    private const val MAX_JOBS = FETCH_SLOTS * 2

    private val slots = Semaphore(MAX_JOBS)

    suspend fun <T> withJob(block: suspend () -> T): T = slots.withPermit { block() }
}
