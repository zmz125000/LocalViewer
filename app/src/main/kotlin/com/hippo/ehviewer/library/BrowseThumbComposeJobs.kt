package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caps how many Compose [androidx.compose.runtime.LaunchedEffect] cells may run
 * heavy extract/download/render at once (video frame, remote image cover, archive
 * page-0, PDF reader / image-reader PDF photo-grid page thumbs).
 *
 * Disk / memory cache probes stay outside [withJob]. Coil paint
 * ([com.hippo.ehviewer.coil.CoverPathFetcher]) is **not** admitted here — sharing
 * the pool let extract waiters starve display so many thumbs appeared in one batch.
 *
 * Publish thumb paths **after** [withJob] returns so the slot is free before Coil starts.
 * Limit is [Settings.browseThumbComposeJobs] (default 8). Raising it wakes waiters.
 */
object BrowseThumbComposeJobs {
    const val DEFAULT_JOBS = 8
    const val MIN_JOBS = 3
    const val MAX_JOBS = 12

    private val mutex = Mutex()
    private var running = 0

    /**
     * One token per freed slot. Must not be conflated: when several jobs finish
     * together, each completion must wake a waiter or concurrency collapses to 1
     * and UI looks like it waits for a whole wave before showing the next.
     */
    private val spaceAvailable = Channel<Unit>(Channel.UNLIMITED)

    /** Unit tests pin the cap without DataStore. */
    @Volatile
    internal var limitOverrideForTest: Int? = null

    fun currentLimit(): Int = (limitOverrideForTest ?: Settings.browseThumbComposeJobs.value)
        .coerceIn(MIN_JOBS, MAX_JOBS)

    /** Pref changed — wake waiters so a higher limit can admit more cells. */
    fun onLimitChanged() {
        repeat(currentLimit()) { spaceAvailable.trySend(Unit) }
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
