package com.hippo.ehviewer.library

import com.hippo.ehviewer.jni.requestArchiveAbort
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes libarchive JNI ([com.hippo.ehviewer.jni.openArchive] is process-global).
 * Reader holds the lock for the whole reading session; cover extract uses [tryWithArchive]
 * and skips when the reader is busy.
 *
 * ## Preemption
 * - **Pending first:** [pendingReaders] is incremented **before** generation so a cover
 *   cannot observe `pending==0` with an already-bumped generation and enter native work.
 * - **Generation:** only the newest [withArchive] proceeds after lock (queued older readers abort).
 * - **holderJob:** cancelled when a new reader arrives so mid-extract covers exit ASAP.
 * - **requestArchiveAbort + abortAction:** cooperative native abort and network source close
 *   so blocking JNI / HTTP Range work unblocks before the mutex wait.
 *
 * Hold only around native archive open/extract/close — never JPEG/UHDR encode.
 */
object ArchiveAccess {
    private val mutex = Mutex()
    private val holderJob = AtomicReference<Job?>(null)

    /** Bumped by every [withArchive] after pending is published; newest wins after lock. */
    private val latestGeneration = AtomicLong(0L)

    /**
     * Count of [withArchive] callers between entry and exit (waiting or holding).
     * Published **before** generation so tryWithArchive cannot race the two atomics.
     */
    private val pendingReaders = AtomicInteger(0)

    /**
     * Optional close hook for the current cover/network [ArchiveByteSource] so a reader
     * can unblock a blocking read by closing the transport, not only canceling coroutines.
     */
    private val abortAction = AtomicReference<(() -> Unit)?>(null)

    /**
     * Register [action] to run when a reader preempts (or immediately if a reader is already
     * pending). Returns a handle that unregisters on [AutoCloseable.close].
     */
    fun registerAbortAction(action: () -> Unit): AutoCloseable {
        abortAction.set(action)
        // Reader racing with registration: fire immediately so cover cannot stick.
        if (pendingReaders.get() > 0) {
            abortAction.getAndSet(null)?.invoke()
        }
        return AutoCloseable {
            abortAction.compareAndSet(action, null)
        }
    }

    /** Exclusive access for reader open → close lifetime. Preempts any prior session. */
    suspend fun <T> withArchive(block: suspend () -> T): T {
        // Order matters: pending must be visible before generation advances.
        pendingReaders.incrementAndGet()
        val myGen = latestGeneration.incrementAndGet()
        try {
            // Unblock in-flight native/network cover work before waiting on the mutex.
            requestArchiveAbort()
            abortAction.getAndSet(null)?.invoke()
            // Cancel current holder (reader or cover). Do not clear holderJob — cover must
            // still receive cancel while it owns the mutex.
            holderJob.get()?.cancel(
                CancellationException("Archive session superseded (gen=$myGen)"),
            )
            return mutex.withLock {
                if (latestGeneration.get() != myGen) {
                    throw CancellationException(
                        "Archive session superseded (stale gen=$myGen, latest=${latestGeneration.get()})",
                    )
                }
                val job = currentCoroutineContext().job
                holderJob.set(job)
                try {
                    currentCoroutineContext().ensureActive()
                    block()
                } finally {
                    holderJob.compareAndSet(job, null)
                }
            }
        } finally {
            pendingReaders.decrementAndGet()
        }
    }

    /**
     * Non-blocking exclusive access for cover extract.
     * Aborts if any reader is pending/holding or generation advanced around acquire.
     * @return null if busy or a reader arrived mid-acquire.
     */
    suspend fun <T> tryWithArchive(block: suspend () -> T): T? {
        if (pendingReaders.get() > 0) return null
        val genBeforeLock = latestGeneration.get()
        if (!mutex.tryLock()) return null
        val job = currentCoroutineContext().job
        holderJob.set(job)
        return try {
            if (pendingReaders.get() > 0) return null
            if (latestGeneration.get() != genBeforeLock) return null
            currentCoroutineContext().ensureActive()
            if (pendingReaders.get() > 0 || latestGeneration.get() != genBeforeLock) {
                return null
            }
            block()
        } finally {
            holderJob.compareAndSet(job, null)
            mutex.unlock()
        }
    }
}
