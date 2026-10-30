package com.hippo.ehviewer.smb

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Active jobs share one connection per server/credential key; the last job closes it. */
internal class SharedSmbUploads<K, V>(private val close: suspend (V) -> Unit) {
    private class Entry<V>(val value: V, var users: Int = 0)
    private val mutex = Mutex()
    private val entries = HashMap<K, Entry<V>>()

    suspend fun <T> withConnection(key: K, open: () -> V, block: suspend (V) -> T): T {
        val entry = mutex.withLock {
            entries.getOrPut(key) { Entry(open()) }.also { it.users++ }
        }
        return try {
            block(entry.value)
        } finally {
            withContext(NonCancellable) {
                val last = mutex.withLock {
                    entry.users--
                    if (entry.users == 0) {
                        entries.remove(key, entry)
                        true
                    } else {
                        false
                    }
                }
                if (last) close(entry.value)
            }
        }
    }
}
