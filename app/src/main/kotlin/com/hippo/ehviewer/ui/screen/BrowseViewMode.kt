package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseContentMode

/**
 * Top-bar menu for folder browsers: content filter preset + list/grid layout.
 * Replaces the old list↔grid toggle icon.
 */
@Composable
fun BrowseViewModeMenu(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val listMode by Settings.listMode.collectAsState()
    var contentModePref by Settings.browseContentMode.asMutableState()
    val contentMode = BrowseContentMode.fromPref(contentModePref)
    val useGrid = listMode == 1

    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            shapes = IconButtonDefaults.shapes(),
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
            ContentModeItem(
                label = stringResource(R.string.browse_mode_media),
                selected = contentMode == BrowseContentMode.Media,
                onClick = {
                    contentModePref = BrowseContentMode.Media.prefValue
                    expanded = false
                },
            )
            ContentModeItem(
                label = stringResource(R.string.browse_mode_galleries),
                selected = contentMode == BrowseContentMode.Galleries,
                onClick = {
                    contentModePref = BrowseContentMode.Galleries.prefValue
                    expanded = false
                },
            )
            ContentModeItem(
                label = stringResource(R.string.browse_mode_video),
                selected = contentMode == BrowseContentMode.Video,
                onClick = {
                    contentModePref = BrowseContentMode.Video.prefValue
                    expanded = false
                },
            )
            ContentModeItem(
                label = stringResource(R.string.browse_mode_folder),
                selected = contentMode == BrowseContentMode.Folder,
                onClick = {
                    contentModePref = BrowseContentMode.Folder.prefValue
                    expanded = false
                },
            )
            HorizontalDivider()
            ContentModeItem(
                label = stringResource(R.string.browse_layout_list),
                selected = !useGrid,
                onClick = {
                    Settings.listMode.value = 0
                    expanded = false
                },
            )
            ContentModeItem(
                label = stringResource(R.string.browse_layout_grid),
                selected = useGrid,
                onClick = {
                    Settings.listMode.value = 1
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun ContentModeItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        } else {
            null
        },
    )
}
