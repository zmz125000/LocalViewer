package com.hippo.ehviewer.ui.reader

/** Newest reader owns the window; disposing an overlapping old reader must not reset it. */
internal class ReaderBrightnessRequests {
    private val requests = linkedMapOf<Any, Int>()
    val isEmpty: Boolean get() = requests.isEmpty()
    private val current: Int get() = requests.values.lastOrNull() ?: 0

    fun set(owner: Any, value: Int): Int {
        requests[owner] = value
        return current
    }

    fun remove(owner: Any): Int {
        requests.remove(owner)
        return current
    }
}
