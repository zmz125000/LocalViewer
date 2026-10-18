package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.HistoryHidePersist
import com.hippo.ehviewer.library.LocalHistory

/** History file/gallery filter ([Settings.historySection]). Directory pins are not filtered. */
enum class HistorySection(val prefValue: Int) {
    Media(0),
    Documents(1),
    ;

    companion object {
        fun fromPref(value: Int): HistorySection = when (value) {
            Documents.prefValue -> Documents
            else -> Media
        }
    }
}

/** Swap Media ↔ Documents. Same as re-tapping History on the bottom nav. */
fun toggleHistorySection() {
    Settings.historySection.value = when (HistorySection.fromPref(Settings.historySection.value)) {
        HistorySection.Media -> HistorySection.Documents.prefValue
        HistorySection.Documents -> HistorySection.Media.prefValue
    }
}

/** Library Recent strip cap. The lazy list only needs the newest page of rows. */
const val LIBRARY_RECENT_LIMIT = 50

/**
 * Library Recent strip. Browse-directory pins stay out (those are folder records).
 * [rows] is already newest-first. A non-blank [query] matches title or Japanese title.
 * [hidden] drops the same rows the History screen hides. Stops at [limit] so a long
 * history does not build the rest of the list.
 */
fun <T : GalleryInfo> libraryRecentHistory(
    rows: List<T>,
    query: String = "",
    limit: Int = LIBRARY_RECENT_LIMIT,
    hidden: (T) -> Boolean = { false },
): List<T> {
    if (rows.isEmpty() || limit <= 0) return emptyList()
    val q = query.trim()
    val out = ArrayList<T>(minOf(limit, rows.size))
    for (info in rows) {
        if (LocalHistory.isBrowseDirectory(info) || hidden(info)) continue
        if (q.isNotEmpty() &&
            info.title?.contains(q, ignoreCase = true) != true &&
            info.titleJpn?.contains(q, ignoreCase = true) != true
        ) {
            continue
        }
        out += info
        if (out.size == limit) break
    }
    return out
}

/**
 * Newest library recents after the History hide-folder filter, capped at [limit].
 * An empty [stored] set skips the place lookup. Otherwise hide checks run in
 * small chunks and stop once [limit] visible rows are collected.
 */
suspend fun visibleLibraryRecents(
    rows: List<GalleryEntity>,
    query: String,
    stored: Set<String>,
    limit: Int = LIBRARY_RECENT_LIMIT,
): List<GalleryEntity> {
    if (stored.isEmpty()) return libraryRecentHistory(rows, query, limit)
    val matched = libraryRecentHistory(rows, query, limit = Int.MAX_VALUE)
    if (matched.isEmpty() || limit <= 0) return emptyList()
    val out = ArrayList<GalleryEntity>(minOf(limit, matched.size))
    var start = 0
    while (start < matched.size && out.size < limit) {
        val end = minOf(start + LIBRARY_RECENT_HIDE_CHUNK, matched.size)
        val shown = HistoryHidePersist.withoutHiddenFolders(matched.subList(start, end), stored)
        for (row in shown) {
            out += row
            if (out.size == limit) break
        }
        start = end
    }
    return out
}

private const val LIBRARY_RECENT_HIDE_CHUNK = 64

fun <T : GalleryInfo> filterHistoryFileItems(
    items: List<T>,
    section: HistorySection,
    allTypes: Boolean = false,
    ebookAsGallery: Boolean = true,
): List<T> = if (allTypes) {
    items
} else {
    items.filter { info ->
        LocalHistory.matchesHistorySection(
            info,
            documents = section == HistorySection.Documents,
            ebookAsGallery = ebookAsGallery,
        )
    }
}
