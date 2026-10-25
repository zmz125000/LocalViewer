package com.hippo.ehviewer.gallery

/** Extra ownership for transition images; an LRU eviction must not recycle them before display. */
internal class TransitionImagePins<T : Any>(
    private val retain: (T) -> Boolean,
    private val release: (T) -> Unit,
) {
    private var wanted = emptySet<Int>()
    private val held = mutableMapOf<Int, T>()

    /** Return already-recycled images so the loader can request a fresh decode. */
    @Synchronized
    fun setWanted(indices: Set<Int>, lookup: (Int) -> T?): Map<Int, T> {
        wanted = indices.toSet()
        held.keys.filterNot(wanted::contains).forEach { held.remove(it)?.let(release) }
        return buildMap {
            wanted.forEach { index ->
                lookup(index)?.let { image -> if (!hold(index, image)) put(index, image) }
            }
        }
    }

    /** Publish status under the same lock as ownership changes, including cache insertion. */
    @Synchronized
    fun publish(index: Int, image: T, publish: () -> Unit) {
        if (index in wanted) hold(index, image)
        publish()
    }

    private fun hold(index: Int, image: T): Boolean {
        if (held[index] === image) return true
        if (!retain(image)) return false
        held.put(index, image)?.let(release)
        return true
    }

    @Synchronized
    fun releaseImages() {
        held.values.forEach(release)
        held.clear()
    }

    @Synchronized
    fun clear() {
        wanted = emptySet()
        releaseImages()
    }
}
