package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.libraryBrowseRelative
import com.hippo.ehviewer.library.stableGalleryId

/** Library gallery secondary sort ([Settings.librarySortMode]). */
enum class LibrarySortMode(val prefValue: Int) {
    Name(0),
    Date(1),
    ;

    companion object {
        fun fromPref(value: Int): LibrarySortMode = when (value) {
            Date.prefValue -> Date
            else -> Name // includes legacy exclusive Last-open (=2)
        }
    }
}

/** Library screen section ([Settings.librarySection]). Tap the section header to swap. */
enum class LibrarySection(val prefValue: Int) {
    Galleries(0),
    Videos(1),
    ;

    companion object {
        fun fromPref(value: Int): LibrarySection = when (value) {
            Videos.prefValue -> Videos
            else -> Galleries
        }
    }
}

/** Video library listing ([Settings.libraryVideoMode]). */
enum class LibraryVideoMode(val prefValue: Int) {
    Folders(0),
    Files(1),
    ;

    companion object {
        fun fromPref(value: Int): LibraryVideoMode = when (value) {
            Files.prefValue -> Files
            else -> Folders
        }
    }
}

fun libraryItemLastOpenTime(item: LocalGalleryEntity, historyTimeByGid: Map<Long, Long>): Long {
    historyTimeByGid[item.id]?.takeIf { it > 0L }?.let { return it }
    return when (item.kind) {
        LOCAL_GALLERY_KIND_VIDEO_FILE ->
            historyTimeByGid[stableGalleryId(0L, "local-file:${item.contentPath}")] ?: 0L
        LOCAL_GALLERY_KIND_VIDEO_FOLDER -> {
            val rel = libraryBrowseRelative(item.relativePath)
            historyTimeByGid[stableGalleryId(item.rootId, "browse:$rel")] ?: 0L
        }
        else -> 0L
    }
}

fun sortLibraryItems(
    items: List<LocalGalleryEntity>,
    sortMode: LibrarySortMode,
    recentOpen: Boolean,
    historyTimeByGid: Map<Long, Long>,
): List<LocalGalleryEntity> = when {
    sortMode == LibrarySortMode.Date && recentOpen ->
        items.sortedWith(
            compareByDescending<LocalGalleryEntity> {
                maxOf(libraryItemLastOpenTime(it, historyTimeByGid), it.mtime)
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    sortMode == LibrarySortMode.Date ->
        items.sortedWith(
            compareByDescending<LocalGalleryEntity> { it.mtime }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    recentOpen ->
        items.sortedWith(
            compareByDescending<LocalGalleryEntity> { libraryItemLastOpenTime(it, historyTimeByGid) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    else ->
        items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

/**
 * Library search-bar view menu (standalone from folder [BrowseViewModeMenu]):
 * - Top: Name / Date sort + Last open
 *   - Name + Last open: HISTORY pin, then title
 *   - Date + Last open: blend max(last-open, scan mtime), then title
 * - Mid: List / Grid layout
 * - Bottom: Photo grid, All videos, zip as folder, back to dir, page count,
 *   reading progress, startup scan
 *
 * Tap icon → menu. Long-press → toggle list ↔ grid.
 */
@Composable
fun LibraryViewModeMenu(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val listMode by Settings.listMode.collectAsState()
    var sortModePref by Settings.librarySortMode.asMutableState()
    val sortMode = LibrarySortMode.fromPref(sortModePref)
    var libraryRecentOpen by Settings.libraryRecentOpen.asMutableState()
    var videoModePref by Settings.libraryVideoMode.asMutableState()
    val videoMode = LibraryVideoMode.fromPref(videoModePref)
    val useGrid = listMode == 1
    var photoGridMode by Settings.photoGridMode.asMutableState()
    var browseZipAsDir by Settings.browseZipAsDir.asMutableState()
    var alwaysExitToDir by Settings.alwaysExitToDir.asMutableState()
    var showGalleryPages by Settings.showGalleryPages.asMutableState()
    var showReadingProgress by Settings.showReadingProgress.asMutableState()
    var libraryStartupScan by Settings.libraryStartupScan.asMutableState()
    val haptic = LocalHapticFeedback.current

    Box(modifier) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .combinedClickable(
                    onClick = { expanded = true },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Settings.listMode.value = if (listMode == 0) 1 else 0
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (useGrid) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.library_view_mode),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LibraryMenuSelectItem(
                label = stringResource(R.string.library_sort_name),
                selected = sortMode == LibrarySortMode.Name,
                onClick = {
                    sortModePref = LibrarySortMode.Name.prefValue
                    expanded = false
                },
            )
            LibraryMenuSelectItem(
                label = stringResource(R.string.library_sort_date),
                selected = sortMode == LibrarySortMode.Date,
                onClick = {
                    sortModePref = LibrarySortMode.Date.prefValue
                    expanded = false
                },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.library_sort_last_open),
                checked = libraryRecentOpen,
                onClick = { libraryRecentOpen = !libraryRecentOpen },
            )
            HorizontalDivider()
            LibraryMenuSelectItem(
                label = stringResource(R.string.browse_layout_list),
                selected = !useGrid,
                onClick = {
                    Settings.listMode.value = 0
                    expanded = false
                },
            )
            LibraryMenuSelectItem(
                label = stringResource(R.string.browse_layout_grid),
                selected = useGrid,
                onClick = {
                    Settings.listMode.value = 1
                    expanded = false
                },
            )
            HorizontalDivider()
            LibraryMenuToggleItem(
                label = stringResource(R.string.browse_menu_photo_grid),
                checked = photoGridMode,
                onClick = { photoGridMode = !photoGridMode },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.library_video_all),
                checked = videoMode == LibraryVideoMode.Files,
                onClick = {
                    videoModePref = if (videoMode == LibraryVideoMode.Files) {
                        LibraryVideoMode.Folders.prefValue
                    } else {
                        LibraryVideoMode.Files.prefValue
                    }
                },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.browse_menu_zip_as_dir),
                checked = browseZipAsDir,
                onClick = { browseZipAsDir = !browseZipAsDir },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.settings_general_back_to_upper_dir),
                checked = alwaysExitToDir,
                onClick = {
                    alwaysExitToDir = !alwaysExitToDir
                    if (alwaysExitToDir) {
                        Settings.historyDirBackToUpper.value = true
                    }
                },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.browse_menu_page_count),
                checked = showGalleryPages,
                onClick = { showGalleryPages = !showGalleryPages },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.browse_menu_reading_progress),
                checked = showReadingProgress,
                onClick = { showReadingProgress = !showReadingProgress },
            )
            LibraryMenuToggleItem(
                label = stringResource(R.string.settings_library_startup_scan),
                checked = libraryStartupScan,
                onClick = { libraryStartupScan = !libraryStartupScan },
            )
        }
    }
}

@Composable
private fun LibraryMenuSelectItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 112.dp, minHeight = 48.dp)
            .combinedClickable(onClick = onClick)
            .padding(MenuDefaults.DropdownMenuItemContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null)
        }
    }
}

@Composable
private fun LibraryMenuToggleItem(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(Icons.Default.Check, contentDescription = null)
            }
        },
    )
}
