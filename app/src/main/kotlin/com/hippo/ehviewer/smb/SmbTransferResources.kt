package com.hippo.ehviewer.smb

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One job/lane connection. Initialization and retirement are safe for concurrent file handles. */
internal class SmbTransferResources<V>(
    private val usable: (V) -> Boolean,
    private val close: (V) -> Unit,
) {
    private var value: V? = null
    private var closed = false
    private val mutex = Mutex()

    suspend fun get(open: suspend () -> V): V = mutex.withLock {
        check(!closed) { "Upload resource already closed" }
        value?.let { if (usable(it)) return@withLock it }
        discardLocked()
        open().also { value = it }
    }

    suspend fun discard(expected: V? = null) = mutex.withLock {
        // A late failure from an old handle must not close its replacement session.
        if (expected == null || value === expected) discardLocked()
    }

    private fun discardLocked() {
        val resource = value
        value = null
        resource?.let(close)
    }

    suspend fun closeAll() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        discardLocked()
    }
}
