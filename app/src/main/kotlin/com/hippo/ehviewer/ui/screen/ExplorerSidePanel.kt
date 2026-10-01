package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.anchoredHorizontalDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.FavoriteBrowseSource
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.SavedExplorerPaths
import com.hippo.ehviewer.library.hideDuplicateGalleriesPreferMediaStore
import com.hippo.ehviewer.library.resolveFavoriteBrowseSources
import com.hippo.ehviewer.library.safFolderLabel
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.WebDavBrowserScreenDestination
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.LocalBrowseListHeaderInset
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.hippo.ehviewer.ui.openLocalFolderPhotoGrid
import com.hippo.ehviewer.ui.openSmbBrowseDir
import com.hippo.ehviewer.ui.openWebDavBrowseDir
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Open and close the explorer panel from the main navigation bar. */
class ExplorerPanelActions {
    var open: () -> Unit = {}
    var close: () -> Unit = {}
    var toggle: () -> Unit = {}
}

val LocalExplorerPanel = compositionLocalOf { ExplorerPanelActions() }

private class PanelCloseGuard {
    var snapOnCancel: Boolean = true
}

private val WindowMaxWidth = 340.dp
private val WindowMaxHeight = 480.dp
private val ExplorerListIconSize = 20.dp
private val ExplorerListIconSizeTablet = 26.dp
private val LocalExplorerTablet = compositionLocalOf { false }
private val ExplorerWindowGap = 8.dp

/**
 * Small floating explorer window (dialog / in-app picture-in-picture).
 * Swipe left to open, swipe right or tap outside to close. The page stays visible around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerSidePanelHost(
    gesturesEnabled: Boolean,
    navigator: DestinationsNavigator,
    currentDestination: Any?,
    browserSourceId: Long?,
    activeFromHistory: Boolean,
    activeFromLibrary: Boolean,
    content: @Composable () -> Unit,
) {
    val panelState = rememberDrawerState2(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeGuard = remember { PanelCloseGuard() }
    var closeJob by remember { mutableStateOf<Job?>(null) }
    fun closePanel() {
        closeGuard.snapOnCancel = true
        if (closeJob?.isActive == true) return
        closeJob = scope.launch {
            try {
                panelState.close()
            } finally {
                if (!closeGuard.snapOnCancel) return@launch
                // A screen slide cancels this animation and leaves the offset mid-way.
                withContext(NonCancellable) {
                    val offset = panelState.currentOffset
                    val closedAt = panelState.anchoredDraggableState.anchors.positionOf(DrawerValue.Closed)
                    if (offset.isNaN() || closedAt.isNaN() || abs(offset - closedAt) > 0.5f) {
                        panelState.snapTo(DrawerValue.Closed)
                    }
                }
            }
        }
    }
    fun openPanel() {
        closeGuard.snapOnCancel = false
        closeJob?.cancel()
        closeJob = scope.launch { panelState.open() }
    }
    fun togglePanel() {
        val expanded = panelState.isOpen || panelState.targetValue == DrawerValue.Open
        if (expanded) {
            closeGuard.snapOnCancel = true
            closeJob?.cancel()
            closeJob = scope.launch {
                try {
                    panelState.close()
                } finally {
                    if (!closeGuard.snapOnCancel) return@launch
                    withContext(NonCancellable) {
                        val offset = panelState.currentOffset
                        val closedAt = panelState.anchoredDraggableState.anchors.positionOf(DrawerValue.Closed)
                        if (offset.isNaN() || closedAt.isNaN() || abs(offset - closedAt) > 0.5f) {
                            panelState.snapTo(DrawerValue.Closed)
                        }
                    }
                }
            }
        } else {
            openPanel()
        }
    }
    val panelActions = LocalExplorerPanel.current
    SideEffect {
        panelActions.open = { openPanel() }
        panelActions.close = { closePanel() }
        panelActions.toggle = { togglePanel() }
    }
    DisposableEffect(panelActions) {
        onDispose {
            panelActions.open = {}
            panelActions.close = {}
            panelActions.toggle = {}
        }
    }
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled && panelState.isOpen) {
            closePanel()
        }
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // Same phone/tablet × portrait/landscape split as list-mode column count.
    val layout = GalleryGridDefaults.listLayout()
    val phonePortrait = layout.columns == 1
    val phoneLandscape = !layout.capReaderSheet
    val tablet = !phonePortrait && !phoneLandscape
    val screenW = configuration.screenWidthDp.dp
    val screenH = configuration.screenHeightDp.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val endPad = if (phonePortrait) 0.dp else 40.dp
        val widthCap = (maxWidth - if (phonePortrait) 0.dp else endPad).coerceAtLeast(0.dp)
        val heightCap = (maxHeight - 16.dp).coerceAtLeast(0.dp)
        val windowWidth = when {
            phonePortrait -> minOf(maxWidth - 16.dp, WindowMaxWidth) * 0.9f
            phoneLandscape -> screenW * 0.5f
            layout.columns == 3 -> screenW / 3f
            else -> screenW * 2f / 3f
        }.let { width ->
            if (phoneLandscape) width else width.coerceIn(0.dp, widthCap)
        }
        val windowHeight = when {
            layout.columns == 3 -> screenH * 2f / 3f
            tablet -> screenH / 2f
            phonePortrait -> minOf(heightCap, WindowMaxHeight)
            else -> heightCap
        }.coerceIn(0.dp, heightCap)
        val endInsetPx = WindowInsets.safeDrawing.getRight(density, LocalLayoutDirection.current)
        val slidePx = with(density) {
            if (phonePortrait) {
                (maxWidth + windowWidth).toPx() / 2f + 16.dp.toPx()
            } else {
                windowWidth.toPx() + endPad.toPx() + endInsetPx + 16.dp.toPx()
            }
        }
        val anchors = remember(slidePx) {
            DraggableAnchors {
                DrawerValue.Closed at slidePx
                DrawerValue.Open at 0f
            }
        }
        LaunchedEffect(anchors) {
            panelState.anchoredDraggableState.updateAnchors(anchors)
        }
        Box(
            Modifier
                .fillMaxSize()
                .anchoredHorizontalDraggable(
                    state = panelState.anchoredDraggableState,
                    enableDragFromStartToEnd = panelState.isOpen,
                    enableDragFromEndToStart = panelState.isClosed,
                    enabled = gesturesEnabled && slidePx > 0f,
                    startDragImmediately = false,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = panelState.anchoredDraggableState,
                        animationSpec = androidx.compose.animation.core.tween(256),
                    ),
                ),
        ) {
            content()
            if (panelState.isOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { closePanel() },
                        ),
                )
            }
            val rawOffset = panelState.currentOffset
            val offset = if (rawOffset.isNaN()) slidePx else rawOffset
            Column(
                Modifier
                    .align(if (phonePortrait) Alignment.TopCenter else Alignment.TopEnd)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            if (phonePortrait) {
                                WindowInsetsSides.Top
                            } else {
                                WindowInsetsSides.Top + WindowInsetsSides.End
                            },
                        ),
                    )
                    .padding(top = 8.dp, end = if (phonePortrait) 0.dp else endPad)
                    .then(
                        if (phoneLandscape) {
                            Modifier.requiredWidth(windowWidth).fillMaxHeight()
                        } else {
                            Modifier.width(windowWidth).height(windowHeight)
                        },
                    )
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .shadow(8.dp, ShapeDefaults.Large)
                    .clip(ShapeDefaults.Large)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ExplorerPanel(
                    navigator = navigator,
                    currentDestination = currentDestination,
                    browserSourceId = browserSourceId,
                    activeFromHistory = activeFromHistory,
                    activeFromLibrary = activeFromLibrary,
                    tablet = tablet,
                    onNavigated = { closePanel() },
                )
            }
        }
    }
}

@Composable
private fun ExplorerPanel(
    navigator: DestinationsNavigator,
    currentDestination: Any?,
    browserSourceId: Long?,
    activeFromHistory: Boolean,
    activeFromLibrary: Boolean,
    tablet: Boolean,
    onNavigated: () -> Unit,
) {
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    val activeId = ExplorerWindows.activeId
    val windows = ExplorerWindows.windows
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val headerStyle = if (tablet) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            }
            val headerIconSize = with(LocalDensity.current) { headerStyle.fontSize.toDp() }
            Text(
                text = stringResource(
                    if (showFavorites) R.string.explorer_quick_access else R.string.explorer,
                ),
                style = headerStyle,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { showFavorites = !showFavorites },
            )
            val duplicateEnabled = activeId != null
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.explorer_duplicate),
                modifier = Modifier
                    .size(headerIconSize)
                    .clickable(
                        enabled = duplicateEnabled,
                        onClick = {
                            val copy = ExplorerWindows.duplicateActive() ?: return@clickable
                            showWindow(
                                navigator,
                                copy,
                                currentDestination,
                                browserSourceId,
                                activeFromHistory,
                                activeFromLibrary,
                            )
                            onNavigated()
                        },
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (duplicateEnabled) 1f else 0.38f,
                ),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showFavorites) {
                ExplorerFavoritesGrid(
                    navigator = navigator,
                    tablet = tablet,
                    onToggleMode = { showFavorites = !showFavorites },
                    onNavigated = onNavigated,
                )
            } else {
                ExplorerWindowList(
                    windows = windows,
                    activeId = activeId,
                    navigator = navigator,
                    currentDestination = currentDestination,
                    browserSourceId = browserSourceId,
                    activeFromHistory = activeFromHistory,
                    activeFromLibrary = activeFromLibrary,
                    tablet = tablet,
                    onToggleMode = { showFavorites = !showFavorites },
                    onNavigated = onNavigated,
                )
            }
        }
    }
}

@Composable
private fun ExplorerWindowList(
    windows: List<ExplorerWindows.Window>,
    activeId: Long?,
    navigator: DestinationsNavigator,
    currentDestination: Any?,
    browserSourceId: Long?,
    activeFromHistory: Boolean,
    activeFromLibrary: Boolean,
    tablet: Boolean,
    onToggleMode: () -> Unit,
    onNavigated: () -> Unit,
) {
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    val smb by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val webDav by WebDavRepository.sourcesFlow().collectAsState(initial = emptyList())
    val savedRaw by Settings.savedExplorerPaths.collectAsState()
    val saved = remember(savedRaw, roots, smb, webDav) {
        SavedExplorerPaths.resolve(roots, smb, webDav)
    }
    val viewingBrowser = isBrowserDestination(currentDestination)
    CompositionLocalProvider(
        LocalBrowseListHeaderInset provides GalleryGridDefaults.margin(),
        LocalExplorerTablet provides tablet,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 8.dp,
                bottom = GalleryGridDefaults.margin(),
            ),
        ) {
            fun closeListedWindow(id: Long) {
                when (val result = ExplorerWindows.close(id)) {
                    ExplorerWindows.CloseResult.Unchanged -> Unit
                    ExplorerWindows.CloseResult.NoneLeft -> {
                        if (viewingBrowser) {
                            navigator.popBackStack()
                            onNavigated()
                        }
                    }
                    is ExplorerWindows.CloseResult.Switched -> {
                        if (viewingBrowser) {
                            showWindow(
                                navigator,
                                result.window,
                                currentDestination,
                                browserSourceId,
                                activeFromHistory,
                                activeFromLibrary,
                            )
                            onNavigated()
                        }
                    }
                }
            }
            if (windows.isNotEmpty()) {
                item(key = "win-hdr") {
                    BrowseSectionHeader(stringResource(R.string.explorer_windows), onClick = onToggleMode)
                }
                items(windows, key = { "w-${it.id}" }) { window ->
                    val source = windowSourceName(window, roots, smb, webDav)
                    val savedAlready = SavedExplorerPaths.contains(window.kind, window.sourceId, window.relativePath)
                    ExplorerPathRow(
                        title = windowRowTitle(window, source),
                        subtitle = windowSubtitle(source, window.relativePath),
                        active = window.id == activeId,
                        modifier = Modifier.padding(bottom = ExplorerWindowGap),
                        onLongClick = { closeListedWindow(window.id) },
                        onClick = {
                            val shown = ExplorerWindows.activate(window.id) ?: return@ExplorerPathRow
                            ExplorerWindows.overrideFromSidePanel(shown.id)
                            val restored = ExplorerWindows.active() ?: return@ExplorerPathRow
                            showWindow(
                                navigator,
                                restored,
                                currentDestination,
                                browserSourceId,
                                activeFromHistory,
                                activeFromLibrary,
                            )
                            onNavigated()
                        },
                        trailing = {
                            ExplorerRowAction(
                                onClick = {
                                    SavedExplorerPaths.remember(window.kind, window.sourceId, window.relativePath)
                                },
                                icon = if (savedAlready) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = stringResource(R.string.explorer_save_path),
                            )
                            ExplorerRowAction(
                                onClick = { closeListedWindow(window.id) },
                                icon = Icons.Default.Close,
                                contentDescription = stringResource(R.string.explorer_close_window),
                            )
                        },
                    )
                }
            }
            item(key = "saved-hdr") {
                BrowseSectionHeader(stringResource(R.string.explorer_saved_paths), onClick = onToggleMode)
            }
            items(saved, key = { "s-${it.key}" }) { item ->
                ExplorerPathRow(
                    title = item.title,
                    subtitle = windowSubtitle(item.sourceName, item.relativePath),
                    active = false,
                    modifier = Modifier.padding(bottom = ExplorerWindowGap),
                    onClick = {
                        openSaved(navigator, item, roots)
                        onNavigated()
                    },
                    trailing = {
                        ExplorerRowAction(
                            onClick = { SavedExplorerPaths.forget(item.key) },
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.explorer_delete_saved),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ExplorerFavoritesGrid(
    navigator: DestinationsNavigator,
    tablet: Boolean,
    onToggleMode: () -> Unit,
    onNavigated: () -> Unit,
) {
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    val smb by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val webDav by WebDavRepository.sourcesFlow().collectAsState(initial = emptyList())
    val galleries by LocalLibrary.galleriesFlow().collectAsState(initial = emptyList())
    val favoriteKeys by Settings.favoriteBrowseSources.collectAsState()
    val visibleGalleries = remember(galleries) { galleries.hideDuplicateGalleriesPreferMediaStore() }
    val favorites = remember(roots, smb, webDav, visibleGalleries, favoriteKeys) {
        resolveFavoriteBrowseSources(roots, smb, webDav, visibleGalleries, favoriteKeys)
    }
    val thumbColumns by Settings.thumbColumns.collectAsState()
    val columnCount = thumbColumns.coerceIn(1, 10)
    val iconSize = if (tablet) ExplorerListIconSizeTablet else ExplorerListIconSize
    val labelStyle = if (tablet) {
        MaterialTheme.typography.labelLarge
    } else {
        MaterialTheme.typography.labelMedium
    }
    val gridState = remember { LazyGridState() }
    FastScrollLazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = GalleryGridDefaults.margin().let { inset ->
            androidx.compose.foundation.layout.PaddingValues(
                start = inset,
                top = 8.dp,
                end = inset,
                bottom = inset,
            )
        },
        verticalArrangement = GalleryGridDefaults.spacedBy(),
        horizontalArrangement = GalleryGridDefaults.spacedBy(),
    ) {
        item(key = "fav-hdr", span = { GridItemSpan(maxLineSpan) }) {
            BrowseSectionHeader(stringResource(R.string.browse_favorites), onClick = onToggleMode)
        }
        items(favorites, key = { "fav-${it.key}" }) { fav ->
            FavoriteSourceGridCell(
                fav = fav,
                columns = columnCount,
                iconSize = iconSize,
                labelStyle = labelStyle,
                onClick = {
                    openFavorite(navigator, fav, roots)
                    onNavigated()
                },
                onLongClick = { toggleFavorite(fav) },
            )
        }
        if (smb.isNotEmpty() || webDav.isNotEmpty()) {
            item(key = "src-net", span = { GridItemSpan(maxLineSpan) }) {
                BrowseSectionHeader(stringResource(R.string.network), onClick = onToggleMode)
            }
            items(smb, key = { "smb-${it.id}" }) { source ->
                FavoriteSourceGridCell(
                    fav = FavoriteBrowseSource.Smb(source),
                    columns = columnCount,
                    iconSize = iconSize,
                    labelStyle = labelStyle,
                    onClick = {
                        openSmbRoot(navigator, source)
                        onNavigated()
                    },
                    onLongClick = { BrowseFavorites.toggleSmb(source.id) },
                )
            }
            items(webDav, key = { "dav-${it.id}" }) { source ->
                FavoriteSourceGridCell(
                    fav = FavoriteBrowseSource.WebDav(source),
                    columns = columnCount,
                    iconSize = iconSize,
                    labelStyle = labelStyle,
                    onClick = {
                        openWebDavRoot(navigator, source)
                        onNavigated()
                    },
                    onLongClick = { BrowseFavorites.toggleWebDav(source.id) },
                )
            }
        }
        if (roots.isNotEmpty()) {
            item(key = "src-dir", span = { GridItemSpan(maxLineSpan) }) {
                BrowseSectionHeader(stringResource(R.string.folder), onClick = onToggleMode)
            }
            items(roots, key = { "root-${it.id}" }) { root ->
                FavoriteSourceGridCell(
                    fav = FavoriteBrowseSource.Local(root),
                    columns = columnCount,
                    iconSize = iconSize,
                    labelStyle = labelStyle,
                    onClick = {
                        openLocalRootWindow(navigator, root)
                        onNavigated()
                    },
                    onLongClick = { BrowseFavorites.toggleLocal(root.id) },
                )
            }
        }
    }
}

@Composable
private fun ExplorerRowAction(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    val tablet = LocalExplorerTablet.current
    IconButton(onClick = onClick, modifier = Modifier.size(if (tablet) 40.dp else 32.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(if (tablet) 22.dp else 18.dp))
    }
}

@Composable
private fun ExplorerPathRow(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val tablet = LocalExplorerTablet.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (tablet) 52.dp else 40.dp)
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(if (tablet) ExplorerListIconSizeTablet else ExplorerListIconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                title,
                style = if (tablet) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = if (tablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

private fun showWindow(
    navigator: DestinationsNavigator,
    window: ExplorerWindows.Window,
    currentDestination: Any?,
    browserSourceId: Long?,
    activeFromHistory: Boolean,
    activeFromLibrary: Boolean,
) {
    val same = when (window.kind) {
        ExplorerWindows.Kind.Local -> currentDestination == FolderBrowserScreenDestination
        ExplorerWindows.Kind.Smb ->
            currentDestination == SmbBrowserScreenDestination && browserSourceId == window.sourceId
        ExplorerWindows.Kind.WebDav ->
            currentDestination == WebDavBrowserScreenDestination && browserSourceId == window.sourceId
    }
    val sameOrigin = window.fromHistory == activeFromHistory &&
        window.fromLibrary == activeFromLibrary
    if (same && sameOrigin) return
    val replace = same && !sameOrigin
    with(navigator) {
        when (window.kind) {
            ExplorerWindows.Kind.Local -> navigate(
                FolderBrowserScreenDestination(
                    fromHistory = window.fromHistory,
                    fromLibrary = window.fromLibrary,
                ),
            ) {
                if (replace) popUpTo(FolderBrowserScreenDestination) { inclusive = true }
                launchSingleTop = true
            }
            ExplorerWindows.Kind.Smb -> navigate(
                SmbBrowserScreenDestination(
                    sourceId = window.sourceId,
                    initialRelativePath = window.relativePath,
                    fromHistory = window.fromHistory,
                    fromLibrary = window.fromLibrary,
                ),
            ) {
                if (replace) popUpTo(SmbBrowserScreenDestination) { inclusive = true }
                launchSingleTop = true
            }
            ExplorerWindows.Kind.WebDav -> navigate(
                WebDavBrowserScreenDestination(
                    sourceId = window.sourceId,
                    initialRelativePath = window.relativePath,
                    fromHistory = window.fromHistory,
                    fromLibrary = window.fromLibrary,
                ),
            ) {
                if (replace) popUpTo(WebDavBrowserScreenDestination) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}

private fun isBrowserDestination(currentDestination: Any?): Boolean = when (currentDestination) {
    FolderBrowserScreenDestination,
    SmbBrowserScreenDestination,
    WebDavBrowserScreenDestination,
    -> true
    else -> false
}

private fun openSaved(
    navigator: DestinationsNavigator,
    item: SavedExplorerPaths.Resolved,
    roots: List<com.ehviewer.core.database.model.LibraryRootEntity>,
) {
    with(navigator) {
        when (item.kind) {
            ExplorerWindows.Kind.Local -> {
                val root = roots.find { it.id == item.sourceId } ?: return
                val path = LocalLibrary.rootPath(root) ?: return
                openLocalBrowseDir(
                    rootId = root.id,
                    rootDisplayName = root.displayName,
                    rootPath = path,
                    relativePath = item.relativePath,
                    preferMediaStore = root.prefersMediaStore,
                    fromSidePanel = true,
                )
            }
            ExplorerWindows.Kind.Smb ->
                openSmbBrowseDir(item.sourceId, item.relativePath, fromSidePanel = true)
            ExplorerWindows.Kind.WebDav ->
                openWebDavBrowseDir(item.sourceId, item.relativePath, fromSidePanel = true)
        }
    }
}

private fun openFavorite(
    navigator: DestinationsNavigator,
    fav: FavoriteBrowseSource,
    roots: List<com.ehviewer.core.database.model.LibraryRootEntity>,
) {
    with(navigator) {
        when (fav) {
            is FavoriteBrowseSource.Local -> {
                val path = LocalLibrary.rootPath(fav.root) ?: return
                openLocalBrowseDir(
                    rootId = fav.root.id,
                    rootDisplayName = fav.root.displayName,
                    rootPath = path,
                    relativePath = "",
                    preferMediaStore = fav.root.prefersMediaStore,
                    fromSidePanel = true,
                )
            }
            is FavoriteBrowseSource.Smb ->
                openSmbBrowseDir(fav.source.id, "", fromSidePanel = true)
            is FavoriteBrowseSource.WebDav ->
                openWebDavBrowseDir(fav.source.id, "", fromSidePanel = true)
            is FavoriteBrowseSource.LocalFolder -> {
                val rootPath = LocalLibrary.rootPath(fav.root) ?: return
                openLocalBrowseDir(
                    rootId = fav.root.id,
                    rootDisplayName = fav.root.displayName,
                    rootPath = rootPath,
                    relativePath = fav.relativePath,
                    preferMediaStore = fav.root.prefersMediaStore,
                    fromSidePanel = true,
                )
            }
            is FavoriteBrowseSource.SmbFolder ->
                openSmbBrowseDir(fav.source.id, fav.relativePath, fromSidePanel = true)
            is FavoriteBrowseSource.WebDavFolder ->
                openWebDavBrowseDir(fav.source.id, fav.relativePath, fromSidePanel = true)
            is FavoriteBrowseSource.Gallery -> openGalleryFavorite(fav, roots)
        }
    }
}

private fun DestinationsNavigator.openGalleryFavorite(
    fav: FavoriteBrowseSource.Gallery,
    roots: List<com.ehviewer.core.database.model.LibraryRootEntity>,
) {
    val gallery = fav.gallery
    if (gallery.kind != LOCAL_GALLERY_KIND_ARCHIVE && Settings.photoGridMode.value) {
        val root = roots.find { it.id == gallery.rootId } ?: return
        val rootPath = LocalLibrary.rootPath(root) ?: return
        openLocalFolderPhotoGrid(
            rootId = root.id,
            rootDisplayName = root.displayName,
            rootPath = rootPath,
            relativePath = gallery.relativePath,
            preferMediaStore = root.prefersMediaStore,
            title = gallery.title,
            fromSidePanel = true,
        )
        return
    }
    val info = gallery.toBaseGalleryInfo()
    if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
        navToReader(gallery.contentPath, info)
    } else {
        navToLocalFolderReader(gallery.contentPath, info)
    }
}

private fun openLocalRootWindow(
    navigator: DestinationsNavigator,
    root: com.ehviewer.core.database.model.LibraryRootEntity,
) {
    val path = LocalLibrary.rootPath(root) ?: return
    ExplorerWindows.prepareSpawn()
    BrowseSession.localStack = listOf(
        BrowseSession.LocalFrame(
            rootId = root.id,
            path = path.toString(),
            title = root.displayName.safFolderLabel(),
            relativePath = "",
            preferMediaStore = root.prefersMediaStore,
        ),
    )
    ExplorerWindows.finishLocalSpawn(
        root.displayName.safFolderLabel(),
        fromSidePanel = true,
    )
    navigator.navigate(FolderBrowserScreenDestination()) { launchSingleTop = true }
}

private fun openSmbRoot(
    navigator: DestinationsNavigator,
    source: com.ehviewer.core.database.model.SmbSourceEntity,
) {
    ExplorerWindows.prepareSpawn()
    BrowseSession.setSmbSegments(source.id, emptyList())
    BrowseSession.setSmbPhotoGrid(source.id, null)
    BrowseSession.setSmbExitToOrigin(source.id, false)
    ExplorerWindows.finishSmbSpawn(
        source.id,
        source.displayName,
        fromSidePanel = true,
    )
    navigator.navigate(
        SmbBrowserScreenDestination(sourceId = source.id, initialRelativePath = ""),
    ) { launchSingleTop = true }
}

private fun openWebDavRoot(
    navigator: DestinationsNavigator,
    source: com.ehviewer.core.database.model.WebDavSourceEntity,
) {
    ExplorerWindows.prepareSpawn()
    BrowseSession.setWebDavSegments(source.id, emptyList())
    BrowseSession.setWebDavPhotoGrid(source.id, null)
    BrowseSession.setWebDavExitToOrigin(source.id, false)
    ExplorerWindows.finishWebDavSpawn(
        source.id,
        source.displayName,
        fromSidePanel = true,
    )
    navigator.navigate(
        WebDavBrowserScreenDestination(sourceId = source.id, initialRelativePath = ""),
    ) { launchSingleTop = true }
}

private fun toggleFavorite(fav: FavoriteBrowseSource) {
    when (fav) {
        is FavoriteBrowseSource.Local -> BrowseFavorites.toggleLocal(fav.root.id)
        is FavoriteBrowseSource.Smb -> BrowseFavorites.toggleSmb(fav.source.id)
        is FavoriteBrowseSource.WebDav -> BrowseFavorites.toggleWebDav(fav.source.id)
        is FavoriteBrowseSource.Gallery -> BrowseFavorites.toggleGallery(fav.gallery.id)
        is FavoriteBrowseSource.LocalFolder ->
            BrowseFavorites.toggleLocalFolder(fav.root.id, fav.relativePath)
        is FavoriteBrowseSource.SmbFolder ->
            BrowseFavorites.toggleSmbFolder(fav.source.id, fav.relativePath)
        is FavoriteBrowseSource.WebDavFolder ->
            BrowseFavorites.toggleWebDavFolder(fav.source.id, fav.relativePath)
    }
}

private fun windowSourceName(
    window: ExplorerWindows.Window,
    roots: List<com.ehviewer.core.database.model.LibraryRootEntity>,
    smb: List<com.ehviewer.core.database.model.SmbSourceEntity>,
    webDav: List<com.ehviewer.core.database.model.WebDavSourceEntity>,
): String {
    val live = when (window.kind) {
        ExplorerWindows.Kind.Local ->
            roots.find { it.id == window.sourceId }?.displayName?.safFolderLabel()
        ExplorerWindows.Kind.Smb -> smb.find { it.id == window.sourceId }?.displayName
        ExplorerWindows.Kind.WebDav -> webDav.find { it.id == window.sourceId }?.displayName
    }
    return live?.takeIf { it.isNotBlank() } ?: window.sourceName
}

private fun windowRowTitle(window: ExplorerWindows.Window, source: String): String {
    if (window.relativePath.isBlank()) return source.ifBlank { window.title }
    return window.title.ifBlank { window.relativePath.substringAfterLast('/') }
}

private fun windowSubtitle(source: String, relativePath: String): String {
    if (relativePath.isBlank()) return source
    if (source.isBlank()) return relativePath
    return source + ExplorerWindows.SUBTITLE_SEP + relativePath
}
