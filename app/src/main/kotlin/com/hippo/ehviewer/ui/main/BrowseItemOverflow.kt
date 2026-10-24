package com.hippo.ehviewer.ui.main

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.shortcuts.FolderHomeShortcut
import com.hippo.ehviewer.shortcuts.FolderShortcutTarget
import com.hippo.ehviewer.ui.LocalSnackBarHostState

enum class BrowseOverflowKind {
    Common,
    Gallery,
    Video,
    Webpage,
    Pdf,
}

enum class BrowseOverflowPlacement {
    ListTrailing,
    GridBottomEnd,
}

/**
 * Overflow actions for a browse list/grid cell.
 * Null callbacks still appear in the menu and call [onUnsupported] (toast),
 * except Share and Open in other app, which are hidden when unset.
 */
data class BrowseOverflowActions(
    val kind: BrowseOverflowKind = BrowseOverflowKind.Common,
    val favorited: Boolean = false,
    val onFavorite: (() -> Unit)? = null,
    val onSaveAs: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onOpenWith: (() -> Unit)? = null,
    val onOpenFolder: (() -> Unit)? = null,
    /** Open this folder as an explorer window and stay on the current one. Null hides the row. */
    val onOpenInNewTab: (() -> Unit)? = null,
    /** Remember this folder under explorer saved paths. Null hides the row. */
    val onSaveToPaths: (() -> Unit)? = null,
    val onInfo: (() -> Unit)? = null,
    val onRead: (() -> Unit)? = null,
    val onPhotoGrid: (() -> Unit)? = null,
    val onPlay: (() -> Unit)? = null,
    val onExternalPlayer: (() -> Unit)? = null,
    val onCopyUrl: (() -> Unit)? = null,
    val onOpenInBrowser: (() -> Unit)? = null,
    val onOpenIncognito: (() -> Unit)? = null,
    val onUnsupported: () -> Unit,
    /** Null hides the item (missing remote source). */
    val onShareViaHttp: (() -> Unit)? = null,
    /** Home-screen pin for this item. Null hides the menu row. */
    val shortcut: FolderShortcutTarget? = null,
    /** JXL, JXR, and camera RAW. Null hides Convert to…. */
    val onConvert: (suspend (StillExportTarget) -> Unit)? = null,
    /** Set on folder-view rows so the overflow can enter select mode. */
    val selectKey: String? = null,
    val selectTitle: String? = null,
    /** Adds this row to one selection HTTP listing. Null means Share via HTTP ignores it. */
    val shareHttp: (suspend (HttpSelectionListing) -> Unit)? = null,
    /** Batch convert into one folder. Null means this row is ignored by Convert to…. */
    val convertStill: (suspend (okio.Path, StillExportTarget) -> Boolean)? = null,
    /** Batch Save to… into one folder. Null means this row is ignored by Save to…. */
    val saveInto: (suspend (okio.Path) -> Boolean)? = null,
    /** Folder icon in the Tasks selection list. */
    val selectFolder: Boolean = false,
    /** Extension, size, and the rest of the folder-list second line. */
    val listLine: BrowseListLine? = null,
)

@Composable
fun BrowseItemOverflowButton(
    actions: BrowseOverflowActions,
    placement: BrowseOverflowPlacement,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var convertMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackBarHostState.current
    val shortcutUnsupported = stringResource(R.string.browse_shortcut_unsupported)
    val grid = placement == BrowseOverflowPlacement.GridBottomEnd
    val more = stringResource(R.string.browse_item_more)
    val iconSize = with(LocalDensity.current) {
        val style = if (grid) {
            MaterialTheme.typography.labelMedium
        } else {
            // ListItem headline uses titleMedium.
            MaterialTheme.typography.titleMedium
        }
        style.fontSize.toDp() * 1.3f
    }
    Box(modifier) {
        val interactionSource = remember { MutableInteractionSource() }
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = more,
            modifier = Modifier
                .size(iconSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = false),
                    role = Role.Button,
                    onClick = { expanded = true },
                ),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                convertMenu = false
            },
        ) {
            fun run(action: (() -> Unit)?) {
                expanded = false
                convertMenu = false
                (action ?: actions.onUnsupported)()
            }
            if (convertMenu && actions.onConvert != null && actions.convertStill != null) {
                val convert = actions.convertStill
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.convert_back)) },
                    onClick = { convertMenu = false },
                )
                listOf(
                    StillExportTarget.AutoOriginal to R.string.convert_auto_original,
                    StillExportTarget.Jpeg to R.string.convert_jpeg,
                    StillExportTarget.JpegHdr to R.string.convert_jpeg_hdr,

                    StillExportTarget.Jxl to R.string.convert_jxl,
                    StillExportTarget.JxlHdr to R.string.convert_jxl_hdr,
                    StillExportTarget.JxlPq to R.string.convert_jxl_pq,

                    StillExportTarget.Avif to R.string.convert_avif,
                    StillExportTarget.AvifGainMap to R.string.convert_avif_gain_map,
                    StillExportTarget.AvifPq to R.string.convert_avif_pq,

                ).filter { (target, _) -> target.available }.forEach { (target, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = {
                            expanded = false
                            convertMenu = false
                            val title = actions.selectTitle?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.convert_to)
                            beginSingleConvert(scope, snackbar, context, title, target, convert)
                        },
                    )
                }
                return@DropdownMenu
            }
            if (actions.selectKey != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_select)) },
                    onClick = {
                        expanded = false
                        FolderSelectHost.current?.enter(actions.toPick())
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_select_all)) },
                    onClick = {
                        expanded = false
                        FolderSelectHost.current?.selectAllListed()
                    },
                )
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
                BrowseOverflowKind.Webpage -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_open_in_browser)) },
                        onClick = { run(actions.onOpenInBrowser) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_open_in_incognito)) },
                        onClick = { run(actions.onOpenIncognito) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_pdf_reader)) },
                        onClick = { run(actions.onPlay) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_copy_url)) },
                        onClick = { run(actions.onCopyUrl) },
                    )
                    HorizontalDivider()
                }
                BrowseOverflowKind.Pdf -> {
                    // Gallery PDFs keep Image reader. Demoted generic PDFs omit [onRead]
                    // so the image reader is blocked (text PDF → built-in PDF reader).
                    if (actions.onRead != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_image_reader)) },
                            onClick = { run(actions.onRead) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_pdf_reader)) },
                        onClick = { run(actions.onPlay) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_external_reader)) },
                        onClick = { run(actions.onExternalPlayer) },
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
                text = { Text(stringResource(R.string.action_save_to)) },
                onClick = { run(actions.onSaveAs) },
            )
            if (actions.onConvert != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.convert_to)) },
                    onClick = { convertMenu = true },
                )
            }
            if (actions.onOpenInNewTab != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_open_in_new_tab)) },
                    onClick = { run(actions.onOpenInNewTab) },
                )
            }
            if (actions.onSaveToPaths != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_save_to_paths)) },
                    onClick = { run(actions.onSaveToPaths) },
                )
            }
            if (actions.onShare != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = { run(actions.onShare) },
                )
            }
            if (actions.onShareViaHttp != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_share_via_http)) },
                    onClick = { run(actions.onShareViaHttp) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browse_open_folder)) },
                onClick = {
                    expanded = false
                    actions.onOpenFolder?.invoke()
                },
            )
            val shortcut = actions.shortcut
            if (shortcut != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_create_shortcut)) },
                    onClick = {
                        expanded = false
                        if (!FolderHomeShortcut.request(context, shortcut)) {
                            Toast.makeText(context, shortcutUnsupported, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            if (actions.onOpenWith != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_in_other_app)) },
                    onClick = { run(actions.onOpenWith) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browse_item_info)) },
                onClick = { run(actions.onInfo) },
            )
        }
    }
}
