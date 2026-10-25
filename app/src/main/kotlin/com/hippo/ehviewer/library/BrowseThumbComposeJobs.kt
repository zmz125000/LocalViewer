package com.hippo.ehviewer.library

import com.hippo.ehviewer.image.hdr.HdrConvertCache

/**
 * Limits document page extraction/rendering started by reader photo grids.
 * Cache probes and Coil display run outside this gate. Video extraction has
 * its own admission gate in [VideoThumbnail.withComposeExtractJob].
 */
object BrowseThumbComposeJobs {
    suspend fun <T> withJob(block: suspend () -> T): T = HdrConvertCache.withThumbConvertSlot(block)
}
