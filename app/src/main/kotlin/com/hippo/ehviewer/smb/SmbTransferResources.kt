package com.hippo.ehviewer.smb

/** One resource owned by a sequential upload job. No pool, multiplex slots, or global state. */
internal class SmbTransferResources<V>(
    private val usable: (V) -> Boolean,
    private val close: (V) -> Unit,
) {
    private var value: V? = null
    private var closed = false

    suspend fun get(open: suspend () -> V): V {
        check(!closed) { "Upload resource already closed" }
        value?.let { if (usable(it)) return it }
        discard()
        return open().also { value = it }
    }

    fun discard() {
        val resource = value
        value = null
        resource?.let(close)
    }

    fun closeAll() {
        if (closed) return
        closed = true
        discard()
    }
}
