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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseContentMode
import com.hippo.ehviewer.library.BrowseFolderId
import com.hippo.ehviewer.library.BrowseModePersist
import com.hippo.ehviewer.library.naturalCompare

/** Folder-view UI sort field ([Settings.browseSortMode]). Separate from [LibrarySortMode]. */
enum class BrowseSortMode(val prefValue: Int) {
    Name(0),
    Date(1),
    ;

    companion object {
        fun fromPref(value: Int): BrowseSortMode = if (value == Date.prefValue) Date else Name
    }
}

/**
 * UI-only folder listing order. Listing / scan / thumbs / open-gallery keep name order.
 */
fun <T> Iterable<T>.sortedForBrowseFolderUi(
    mode: BrowseSortMode,
    ascending: Boolean,
    nameOf: (T) -> String,
    dateOf: (T) -> Long,
): List<T> {
    val nameCmp = Comparator<T> { a, b -> naturalCompare(nameOf(a), nameOf(b)) }
    val cmp = when (mode) {
        BrowseSortMode.Name -> nameCmp
        BrowseSortMode.Date -> Comparator { a, b ->
            val byDate = dateOf(a).compareTo(dateOf(b))
            if (byDate != 0) byDate else naturalCompare(nameOf(a), nameOf(b))
        }
    }
    return sortedWith(if (ascending) cmp else cmp.reversed())
}

/**
 * Effective content filter for [folder]: own persist, inherited persist, RAM override,
 * or the global pref.
 */
@Composable
fun rememberEffectiveBrowseContentMode(
    folder: BrowseFolderId?,
    skipAncestorKeys: Set<String> = emptySet(),
): BrowseContentMode {
    val persistRev by BrowseModePersist.revision.collectAsState()
    val persistModes by Settings.persistBrowseModes.collectAsState()
    val globalPref by Settings.browseContentMode.collectAsState()
    return remember(folder, persistRev, persistModes, globalPref, skipAncestorKeys) {
        BrowseModePersist.effective(folder, skipAncestorKeys)
            ?: BrowseContentMode.fromPref(globalPref)
    }
}

/**
 * View-mode control for folder browsers: content filter, list/grid layout,
 * Name/Date UI sort (separate from Library), and display toggles.
 *
 * - **Tap** the icon → open this menu.
 * - **Long-press** the icon → toggle list ↔ grid ([Settings.listMode]).
 *
 * [folder] is the current directory identity. Null (root picker / Library / History)
 * disables per-folder persist for content modes (global pref still applies).
 * [hideContentModes] hides Media/Galleries/Video/Folder for [BrowseVirtualKind] layers
 * (RPC share list, photo grid) — virtual listings, not regular folder-view mode.
 */
@Composable
fun BrowseViewModeMenu(
    modifier: Modifier = Modifier,
    folder: BrowseFolderId? = null,
    skipAncestorKeys: Set<String> = emptySet(),
    hideContentModes: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val listMode by Settings.listMode.collectAsState()
    val persistRev by BrowseModePersist.revision.collectAsState()
    val persistModes by Settings.persistBrowseModes.collectAsState()
    var contentModePref by Settings.browseContentMode.asMutableState()
    val match = remember(folder, persistRev, persistModes, skipAncestorKeys) {
        folder?.let { BrowseModePersist.resolve(it, skipAncestorKeys) }
    }
    val contentMode = match?.effective ?: BrowseContentMode.fromPref(contentModePref)
    val useGrid = listMode == 1
    var browseSortModePref by Settings.browseSortMode.asMutableState()
    var browseSortAscending by Settings.browseSortAscending.asMutableState()
    val browseSortMode = BrowseSortMode.fromPref(browseSortModePref)
    var browseFolderThumbs by Settings.browseFolderThumbs.asMutableState()
    var showSmallGalleries by Settings.browseShowSmallGalleries.asMutableState()
    var showHiddenFiles by Settings.browseShowHiddenFiles.asMutableState()
    var showVirtualGalleries by Settings.browseShowVirtualGalleries.asMutableState()
    var favoritesOnTop by Settings.browseFavoritesOnTop.asMutableState()
    var photoGridMode by Settings.photoGridMode.asMutableState()
    val haptic = LocalHapticFeedback.current

    fun selectBrowseSort(mode: BrowseSortMode) {
        if (browseSortMode == mode) {
            browseSortAscending = !browseSortAscending
        } else {
            browseSortModePref = mode.prefValue
        }
    }

    Box(modifier) {
        // IconButton has no onLongClick; match its 48dp target with combinedClickable.
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
                contentDescription = stringResource(R.string.browse_view_mode),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (!hideContentModes) {
                ContentModeItem(
                    label = stringResource(R.string.browse_mode_media),
                    mark = markFor(BrowseContentMode.Media, contentMode, match?.showLock == true),
                    onClick = {
                        BrowseModePersist.tap(folder, BrowseContentMode.Media, skipAncestorKeys)
                    },
                    onLongClick = {
                        BrowseModePersist.longPress(folder, BrowseContentMode.Media, skipAncestorKeys)
                    },
                )
                ContentModeItem(
                    label = stringResource(R.string.browse_mode_galleries),
                    mark = markFor(BrowseContentMode.Galleries, contentMode, match?.showLock == true),
                    onClick = {
                        BrowseModePersist.tap(folder, BrowseContentMode.Galleries, skipAncestorKeys)
                    },
                    onLongClick = {
                        BrowseModePersist.longPress(folder, BrowseContentMode.Galleries, skipAncestorKeys)
                    },
                )
                ContentModeItem(
                    label = stringResource(R.string.browse_mode_video),
                    mark = markFor(BrowseContentMode.Video, contentMode, match?.showLock == true),
                    onClick = {
                        BrowseModePersist.tap(folder, BrowseContentMode.Video, skipAncestorKeys)
                    },
                    onLongClick = {
                        BrowseModePersist.longPress(folder, BrowseContentMode.Video, skipAncestorKeys)
                    },
                )
                ContentModeItem(
                    label = stringResource(R.string.browse_mode_folder),
                    mark = markFor(BrowseContentMode.Folder, contentMode, match?.showLock == true),
                    onClick = {
                        BrowseModePersist.tap(folder, BrowseContentMode.Folder, skipAncestorKeys)
                    },
                    onLongClick = {
                        BrowseModePersist.longPress(folder, BrowseContentMode.Folder, skipAncestorKeys)
                    },
                )
                HorizontalDivider()
            }
            ContentModeItem(
                label = stringResource(R.string.browse_layout_list),
                mark = if (!useGrid) ModeMark.Tick else ModeMark.None,
                onClick = {
                    Settings.listMode.value = 0
                    expanded = false
                },
            )
            ContentModeItem(
                label = stringResource(R.string.browse_layout_grid),
                mark = if (useGrid) ModeMark.Tick else ModeMark.None,
                onClick = {
                    Settings.listMode.value = 1
                    expanded = false
                },
            )
            // Under List/Grid: Name / Date with ↑↓ (folder UI only; not Library sort).
            SortMenuItem(
                label = stringResource(R.string.library_sort_name),
                selected = browseSortMode == BrowseSortMode.Name,
                ascending = browseSortAscending,
                onClick = { selectBrowseSort(BrowseSortMode.Name) },
            )
            SortMenuItem(
                label = stringResource(R.string.library_sort_date),
                selected = browseSortMode == BrowseSortMode.Date,
                ascending = browseSortAscending,
                onClick = { selectBrowseSort(BrowseSortMode.Date) },
            )
            HorizontalDivider()
            ToggleMenuItem(
                label = stringResource(R.string.browse_menu_photo_grid),
                checked = photoGridMode,
                onClick = { photoGridMode = !photoGridMode },
            )
            ToggleMenuItem(
                label = stringResource(R.string.browse_menu_favorites_on_top),
                checked = favoritesOnTop,
                onClick = { favoritesOnTop = !favoritesOnTop },
            )
            ToggleMenuItem(
                label = stringResource(R.string.browse_folder_thumbs),
                checked = browseFolderThumbs,
                onClick = { browseFolderThumbs = !browseFolderThumbs },
            )
            ToggleMenuItem(
                label = stringResource(R.string.browse_menu_small_galleries),
                checked = showSmallGalleries,
                onClick = { showSmallGalleries = !showSmallGalleries },
            )
            ToggleMenuItem(
                label = stringResource(R.string.browse_menu_virtual_galleries),
                checked = showVirtualGalleries,
                onClick = { showVirtualGalleries = !showVirtualGalleries },
            )
            ToggleMenuItem(
                label = stringResource(R.string.browse_menu_hidden_files),
                checked = showHiddenFiles,
                onClick = { showHiddenFiles = !showHiddenFiles },
            )
        }
    }
}

private enum class ModeMark { None, Tick, Lock }

private fun markFor(
    mode: BrowseContentMode,
    effective: BrowseContentMode,
    lockOnEffective: Boolean,
): ModeMark = when {
    mode != effective -> ModeMark.None
    lockOnEffective -> ModeMark.Lock
    else -> ModeMark.Tick
}

@Composable
private fun ContentModeItem(
    label: String,
    mark: ModeMark,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    // Do not use DropdownMenuItem — its clickable consumes the press and
    // long-press on a modifier never fires.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 112.dp, minHeight = 48.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { longClick ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        longClick()
                    }
                },
            )
            .padding(MenuDefaults.DropdownMenuItemContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        MenuMarkSlot(mark)
    }
}

/** Name / Date row: selected shows ↑ (asc) or ↓ (desc); retap flips direction. */
@Composable
private fun SortMenuItem(
    label: String,
    selected: Boolean,
    ascending: Boolean,
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
            if (selected) {
                Icon(
                    imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                )
            }
        }
    }
}

/** Toggle that stays open so several settings can be flipped without reopening. */
@Composable
private fun ToggleMenuItem(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { MenuMarkSlot(if (checked) ModeMark.Tick else ModeMark.None) },
    )
}

/** Fixed-width trailing slot so menu width does not jump when the tick/lock appears. */
@Composable
private fun MenuMarkSlot(mark: ModeMark) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (mark) {
            ModeMark.None -> Unit
            ModeMark.Tick -> Icon(Icons.Default.Check, contentDescription = null)
            ModeMark.Lock -> Icon(Icons.Default.Lock, contentDescription = null)
        }
    }
}
