package com.hippo.ehviewer.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes libarchive JNI ([com.hippo.ehviewer.jni.openArchive] is process-global).
 * Reader holds the lock for the whole reading session; cover extract uses [tryWithArchive]
 * and skips when the reader is busy.
 */
object ArchiveAccess {
    private val mutex = Mutex()

    /** Exclusive access for reader open → close lifetime. */
    suspend fun <T> withArchive(block: suspend () -> T): T = mutex.withLock { block() }

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
