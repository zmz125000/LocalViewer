package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.database.model.isLibraryGalleryKind
import com.ehviewer.core.database.model.isLibraryVideoKind
import com.hippo.ehviewer.library.hideDuplicateGalleriesPreferMediaStore

/**
 * Last library list kept in process RAM.
 *
 * The tab paints this snapshot on the first frame. Filter, dedupe, and sort run
 * only when this snapshot does not already match, and they run off the main thread.
 *
 * A huge library is the full LOCAL_GALLERIES table, including every image and video
 * file row. The grid only composes visible cells, so the row count does not layout
 * the whole list. The cost is the table read, path dedupe, and the sort. Those stay
 * off the tab switch.
 */
internal object LibraryListMemory {
    private val gate = Any()
    private var rawRef: List<LocalGalleryEntity> = emptyList()
    private var loadedFlag = false
    private var visibleSource: List<LocalGalleryEntity>? = null
    private var visibleCached: List<LocalGalleryEntity> = emptyList()
    private var hasGalleriesCached = false
    private var hasVideosCached = false
    private var prepared: LibraryListBuild? = null

    val loaded: Boolean get() = synchronized(gate) { loadedFlag }

    fun raw(): List<LocalGalleryEntity> = synchronized(gate) { rawRef }

    fun storeRaw(list: List<LocalGalleryEntity>) {
        synchronized(gate) {
            rawRef = list
            loadedFlag = true
        }
    }

    /** Exact snapshot for [raw] and [key], or the last same view while a refresh runs. */
    fun display(raw: List<LocalGalleryEntity>, key: LibraryListKey): LibraryListBuild? = synchronized(gate) {
        val hit = prepared ?: return null
        if (hit.key == key && hit.raw === raw) return hit
        if (hit.key.sameView(key)) return hit
        null
    }

    fun peek(raw: List<LocalGalleryEntity>, key: LibraryListKey): LibraryListBuild? = synchronized(gate) {
        val hit = prepared ?: return null
        if (hit.raw === raw && hit.key == key) hit else null
    }

    fun prepare(
        raw: List<LocalGalleryEntity>,
        key: LibraryListKey,
        history: Map<Long, Long>,
    ): LibraryListBuild {
        peek(raw, key)?.let { return it }
        val (visible, hasGalleries, hasVideos) = visibleFacts(raw)
        val items = buildShownLibraryItems(visible, key, history)
        val built = LibraryListBuild(
            raw = raw,
            key = key,
            items = items,
            visible = visible,
            hasGalleries = hasGalleries,
            hasVideos = hasVideos,
        )
        synchronized(gate) {
            val current = prepared
            if (current != null && current.raw === raw && current.key == key) return current
            prepared = built
        }
        return built
    }

    fun resetForTest() {
        synchronized(gate) {
            rawRef = emptyList()
            loadedFlag = false
            visibleSource = null
            visibleCached = emptyList()
            hasGalleriesCached = false
            hasVideosCached = false
            prepared = null
        }
    }

    private fun visibleFacts(raw: List<LocalGalleryEntity>): Triple<List<LocalGalleryEntity>, Boolean, Boolean> {
        synchronized(gate) {
            if (visibleSource === raw) {
                return Triple(visibleCached, hasGalleriesCached, hasVideosCached)
            }
        }
        val visible = if (raw.size <= 1) raw else raw.hideDuplicateGalleriesPreferMediaStore()
        var hasGalleries = false
        var hasVideos = false
        for (item in visible) {
            if (!hasGalleries && isLibraryGalleryKind(item.kind)) hasGalleries = true
            if (!hasVideos && isLibraryVideoKind(item.kind)) hasVideos = true
            if (hasGalleries && hasVideos) break
        }
        synchronized(gate) {
            if (visibleSource !== raw) {
                visibleSource = raw
                visibleCached = visible
                hasGalleriesCached = hasGalleries
                hasVideosCached = hasVideos
            }
            return Triple(visibleCached, hasGalleriesCached, hasVideosCached)
        }
    }
}

internal data class LibraryListKey(
    val keyword: String,
    val recentOpen: Boolean,
    val sortMode: LibrarySortMode,
    val section: LibrarySection,
    val videoMode: LibraryVideoMode,
    val photoMode: LibraryPhotoMode,
    val historyEpoch: Int,
) {
    fun sameView(other: LibraryListKey): Boolean = copy(historyEpoch = 0) == other.copy(historyEpoch = 0)
}

internal data class LibraryListBuild(
    val raw: List<LocalGalleryEntity>,
    val key: LibraryListKey,
    val items: List<LocalGalleryEntity>,
    val visible: List<LocalGalleryEntity>,
    val hasGalleries: Boolean,
    val hasVideos: Boolean,
)

internal fun buildShownLibraryItems(
    visible: List<LocalGalleryEntity>,
    key: LibraryListKey,
    history: Map<Long, Long>,
): List<LocalGalleryEntity> {
    val kindFiltered = filterLibraryItems(visible, key.section, key.videoMode, key.photoMode)
    val filtered = if (key.keyword.isEmpty()) {
        kindFiltered
    } else {
        kindFiltered.filter { it.title.contains(key.keyword, ignoreCase = true) }
    }
    val flatten = libraryFlattenPhotos(kindFiltered, key.section, key.photoMode)
    return sortLibraryItems(
        filtered,
        if (flatten) LibrarySortMode.Date else key.sortMode,
        recentOpen = if (flatten) false else key.recentOpen,
        history,
    )
}
