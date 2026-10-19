package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caps how many Compose [androidx.compose.runtime.LaunchedEffect] cells may run
 * heavy thumb work at once (browse video/image/archive covers, library/history
 * archive page-0 extract).
 *
 * Disk / memory cache probes stay outside [withJob] so a full grid of hits does not
 * serialize. Scroll-away cancels waiters and frees a permit.
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
