package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caps how many heavy thumb jobs may run at once:
 * - Browse [androidx.compose.runtime.LaunchedEffect] video / remote-image / archive work
 * - Library/history archive page-0 extract
 * - Coil [com.hippo.ehviewer.coil.CoverPathFetcher] cache misses (local image thumbs)
 *
 * Disk / memory cache probes and Coil memory hits stay outside [withJob].
 * Scroll-away / request cancel frees a permit.
 *
 * Limit is [Settings.browseThumbComposeJobs] (default 8). Raising it wakes waiters.
 */
object BrowseThumbComposeJobs {
    const val DEFAULT_JOBS = 8
    const val MIN_JOBS = 3
    const val MAX_JOBS = 12

    private val mutex = Mutex()
    private var running = 0
    private val spaceAvailable = Channel<Unit>(Channel.CONFLATED)

    /** Unit tests pin the cap without DataStore. */
    @Volatile
    internal var limitOverrideForTest: Int? = null

    fun currentLimit(): Int = (limitOverrideForTest ?: Settings.browseThumbComposeJobs.value)
        .coerceIn(MIN_JOBS, MAX_JOBS)

    /** Pref changed — wake waiters so a higher limit can admit more cells. */
    fun onLimitChanged() {
        spaceAvailable.trySend(Unit)
    }

    suspend fun <T> withJob(block: suspend () -> T): T {
        while (true) {
            val got = mutex.withLock {
                if (running < currentLimit()) {
                    running++
                    true
                } else {
                    false
                }
            }
            if (got) break
            spaceAvailable.receive()
        }
        try {
            return block()
        } finally {
            mutex.withLock { running-- }
            spaceAvailable.trySend(Unit)
        }
    }
}
