package com.hippo.ehviewer.library

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes libarchive JNI ([com.hippo.ehviewer.jni.openArchive] is process-global).
 * Reader holds the lock for the whole reading session; cover extract uses [tryWithArchive]
 * and skips when the reader is busy.
 *
 * A new [withArchive] **preempts** the previous holder so exit / double-tap prev-next can
 * drop a long solid extract and open the next archive without waiting for full decompress.
 * Callers must check cancellation between blocking JNI steps.
 */
object ArchiveAccess {
    private val mutex = Mutex()
    private val holderJob = AtomicReference<Job?>(null)

    /** Exclusive access for reader open → close lifetime. Preempts any prior session. */
    suspend fun <T> withArchive(block: suspend () -> T): T {
        // Cancel the previous reader/extract session so it releases the lock ASAP.
        holderJob.getAndSet(null)?.cancel(
            CancellationException("Archive session superseded"),
        )
        return mutex.withLock {
            val job = currentCoroutineContext().job
            holderJob.set(job)
            try {
                block()
            } finally {
                holderJob.compareAndSet(job, null)
            }
        }
    }

    /**
     * Non-blocking exclusive access for cover extract.
     * @return null if the archive engine is busy (reader open).
     */
    suspend fun <T> tryWithArchive(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
