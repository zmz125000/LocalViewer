package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R

enum class BrowseOverflowKind {
    Common,
    Gallery,
    Video,
}

enum class BrowseOverflowPlacement {
    ListTrailing,
    GridBottomEnd,
}

/**
 * Overflow actions for a browse list/grid cell.
 * Null callbacks still appear in the menu and call [onUnsupported] (toast).
 */
data class BrowseOverflowActions(
    val kind: BrowseOverflowKind = BrowseOverflowKind.Common,
    val favorited: Boolean = false,
    val onFavorite: (() -> Unit)? = null,
    val onDownload: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onOpenWith: (() -> Unit)? = null,
    val onInfo: (() -> Unit)? = null,
    val onRead: (() -> Unit)? = null,
    val onPhotoGrid: (() -> Unit)? = null,
    val onPlay: (() -> Unit)? = null,
    val onExternalPlayer: (() -> Unit)? = null,
    val onCopyUrl: (() -> Unit)? = null,
    val onUnsupported: () -> Unit,
)

@Composable
fun BrowseItemOverflowButton(
    actions: BrowseOverflowActions,
    placement: BrowseOverflowPlacement,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val grid = placement == BrowseOverflowPlacement.GridBottomEnd
    val more = stringResource(R.string.browse_item_more)
    val iconSize = with(LocalDensity.current) {
        val style = if (grid) {
            MaterialTheme.typography.labelMedium
        } else {
            // ListItem headline uses titleMedium.
            MaterialTheme.typography.titleMedium
        }
        style.fontSize.toDp()
    }
    Box(modifier) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = more,
            modifier = Modifier
                .then(if (grid) Modifier.padding(start = 4.dp) else Modifier)
                .size(iconSize)
                .clickable(role = Role.Button, onClick = { expanded = true }),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            fun run(action: (() -> Unit)?) {
                expanded = false
                (action ?: actions.onUnsupported)()
            }
            when (actions.kind) {
                BrowseOverflowKind.Gallery -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.read)) },
                        onClick = { run(actions.onRead) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_menu_photo_grid)) },
                        onClick = { run(actions.onPhotoGrid) },
                    )
                    HorizontalDivider()
                }
                BrowseOverflowKind.Video -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_play)) },
                        onClick = { run(actions.onPlay) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_external_player)) },
                        onClick = { run(actions.onExternalPlayer) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_copy_url)) },
                        onClick = { run(actions.onCopyUrl) },
                    )
                    HorizontalDivider()
                }
                BrowseOverflowKind.Common -> Unit
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (actions.favorited) {
                                R.string.remove_from_favourites
                            } else {
                                R.string.add_to_favourites
                            },
                        ),
                    )
                },
                onClick = { run(actions.onFavorite) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download)) },
                onClick = { run(actions.onDownload) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                onClick = { run(actions.onShare) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_in_other_app)) },
                onClick = { run(actions.onOpenWith) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browse_item_info)) },
                onClick = { run(actions.onInfo) },
            )
        }
    }
}
