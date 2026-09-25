package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
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

fun <T : GalleryInfo> filterHistoryFileItems(
    items: List<T>,
    section: HistorySection,
    allTypes: Boolean = false,
): List<T> = if (allTypes) {
    items
} else {
    items.filter { info ->
        LocalHistory.matchesHistorySection(info, section == HistorySection.Documents)
    }
}
