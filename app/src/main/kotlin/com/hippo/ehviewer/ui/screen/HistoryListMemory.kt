package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.library.LocalHistory

/**
 * Last History list kept in process RAM.
 *
 * The tab paints this snapshot on the first frame. Keyword, directory, and
 * Media/Documents filtering run off the main thread when the snapshot is stale.
 * The grid only composes visible rows, so a long history is costly in the
 * full-list scan, not in laying out every row.
 */
internal object HistoryListMemory {
    private val gate = Any()
    private var sourceRef: List<*> = emptyList<GalleryInfo>()
    private var loadedFlag = false
    private var prepared: HistoryListBuild<*>? = null

    /** List/grid scroll across tab switches (History nav entry is destroyed on leave). */
    private val scroll = mutableMapOf<String, HistoryScrollPosition>()

    val loaded: Boolean get() = synchronized(gate) { loadedFlag }

    fun store(list: List<*>) {
        synchronized(gate) {
            sourceRef = list
            loadedFlag = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : GalleryInfo> source(): List<T> = synchronized(gate) { sourceRef as List<T> }

    /** Exact snapshot, or the last list for this view while a newer source is prepared. */
    @Suppress("UNCHECKED_CAST")
    fun <T : GalleryInfo> display(source: List<T>, key: HistoryListKey): HistoryListBuild<T>? = synchronized(gate) {
        val hit = prepared ?: return null
        if (hit.key != key) return null
        if (hit.source !== source && source.isEmpty()) return null
        hit as HistoryListBuild<T>
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : GalleryInfo> peek(source: List<T>, key: HistoryListKey): HistoryListBuild<T>? = synchronized(gate) {
        val hit = prepared ?: return null
        if (hit.source === source && hit.key == key) hit as HistoryListBuild<T> else null
    }

    fun <T : GalleryInfo> prepare(source: List<T>, key: HistoryListKey): HistoryListBuild<T> {
        peek(source, key)?.let { return it }
        val built = buildHistoryList(source, key)
        synchronized(gate) {
            val current = prepared
            if (current != null && current.source === source && current.key == key) {
                @Suppress("UNCHECKED_CAST")
                return current as HistoryListBuild<T>
            }
            prepared = built
        }
        return built
    }

    fun saveScroll(listMode: Int, section: HistorySection, index: Int, offset: Int) {
        synchronized(gate) {
            scroll[scrollKey(listMode, section)] =
                HistoryScrollPosition(index, offset.coerceAtLeast(0))
        }
    }

    fun scroll(listMode: Int, section: HistorySection): HistoryScrollPosition? = synchronized(gate) {
        scroll[scrollKey(listMode, section)]
    }

    fun resetForTest() {
        synchronized(gate) {
            sourceRef = emptyList<GalleryInfo>()
            loadedFlag = false
            prepared = null
            scroll.clear()
        }
    }

    private fun scrollKey(listMode: Int, section: HistorySection) = "$listMode#${section.name}"
}

internal data class HistoryScrollPosition(val index: Int, val offset: Int = 0)

internal data class HistoryListKey(
    val keyword: String,
    val section: HistorySection,
    val ebookAsGallery: Boolean,
)

internal data class HistoryListBuild<T : GalleryInfo>(
    val source: List<T>,
    val key: HistoryListKey,
    val directories: List<T>,
    val files: List<T>,
    val shownFiles: List<T>,
)

internal fun <T : GalleryInfo> buildHistoryList(source: List<T>, key: HistoryListKey): HistoryListBuild<T> {
    val filtered = if (key.keyword.isEmpty()) {
        source
    } else {
        source.filter { info ->
            info.title?.contains(key.keyword, ignoreCase = true) == true ||
                info.titleJpn?.contains(key.keyword, ignoreCase = true) == true
        }
    }
    val directories = ArrayList<T>()
    val files = ArrayList<T>()
    for (info in filtered) {
        if (LocalHistory.isBrowseDirectory(info)) directories.add(info) else files.add(info)
    }
    val shown = filterHistoryFileItems(
        files,
        key.section,
        allTypes = key.keyword.isNotEmpty(),
        ebookAsGallery = key.ebookAsGallery,
    )
    return HistoryListBuild(
        source = source,
        key = key,
        directories = directories,
        files = files,
        shownFiles = shown,
    )
}
