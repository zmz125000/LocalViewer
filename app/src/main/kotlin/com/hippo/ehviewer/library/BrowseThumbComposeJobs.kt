package com.hippo.ehviewer.library

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Limits document page extraction/rendering started by reader photo grids.
 * Cache probes and Coil display run outside this gate. Video extraction has
 * its own admission gate in [VideoThumbnail.withComposeExtractJob].
 */
object BrowseThumbComposeJobs {
    private val slots = Semaphore(8)

    suspend fun <T> withJob(block: suspend () -> T): T = slots.withPermit { block() }
}
