package com.hippo.ehviewer.library

/**
 * Absolute image paths for the All photos flatten reader.
 * Kept out of nav args so a large library does not blow the binder limit.
 */
object ReaderImageList {
    @Volatile
    var paths: List<String> = emptyList()
        private set

    fun set(list: List<String>) {
        paths = list
    }

    fun clear() {
        paths = emptyList()
    }
}
