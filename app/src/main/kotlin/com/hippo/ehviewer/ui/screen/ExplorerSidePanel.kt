package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.anchoredHorizontalDraggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ShapeDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import com.hippo.ehviewer.ui.main.BrowseFolderListItem
import com.hippo.ehviewer.ui.main.BrowseListLeadingIconSize
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.hippo.ehviewer.ui.openLocalFolderPhotoGrid
import com.hippo.ehviewer.ui.openSmbBrowseDir
import com.hippo.ehviewer.ui.openWebDavBrowseDir
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val WindowMaxWidth = 340.dp
private val WindowMaxHeight = 480.dp

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
    content: @Composable () -> Unit,
) {
    val panelState = rememberDrawerState2(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled && panelState.isOpen) {
            panelState.close()
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = minOf(maxWidth - 16.dp, WindowMaxWidth).coerceAtLeast(0.dp)
        val windowHeight = minOf(maxHeight - 96.dp, WindowMaxHeight).coerceAtLeast(0.dp)
        val slidePx = with(density) { (windowWidth + 24.dp).toPx() }
        SideEffect {
            panelState.anchoredDraggableState.updateAnchors(
                DraggableAnchors {
                    DrawerValue.Closed at slidePx
                    DrawerValue.Open at 0f
                },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .anchoredHorizontalDraggable(
                    state = panelState.anchoredDraggableState,
                    enableDragFromStartToEnd = panelState.isOpen,
                    enableDragFromEndToStart = panelState.isClosed,
                    enabled = gesturesEnabled && slidePx > 0f,
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
                            onClick = { scope.launch { panelState.close() } },
                        ),
                )
            }
            val rawOffset = panelState.currentOffset
            val offset = if (rawOffset.isNaN()) slidePx else rawOffset
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                    .padding(top = 8.dp, end = 8.dp)
                    .width(windowWidth)
                    .height(windowHeight)
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .shadow(8.dp, ShapeDefaults.Large)
                    .clip(ShapeDefaults.Large)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ExplorerPanel(
                    navigator = navigator,
                    currentDestination = currentDestination,
                    browserSourceId = browserSourceId,
                    onNavigated = { scope.launch { panelState.close() } },
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
    onNavigated: () -> Unit,
) {
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    val activeId = ExplorerWindows.activeId
    val windows = ExplorerWindows.windows
    Column(Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.explorer),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .clickable { showFavorites = !showFavorites }
                .padding(vertical = 12.dp),
        )
        IconButton(
            onClick = {
                val copy = ExplorerWindows.duplicateActive() ?: return@IconButton
                showWindow(navigator, copy, currentDestination, browserSourceId)
                onNavigated()
            },
            enabled = activeId != null,
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.explorer_duplicate),
            )
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (showFavorites) {
            ExplorerFavoritesGrid(
                navigator = navigator,
                onNavigated = onNavigated,
            )
        } else {
            ExplorerWindowList(
                windows = windows,
                activeId = activeId,
                navigator = navigator,
                currentDestination = currentDestination,
                browserSourceId = browserSourceId,
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
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "win-hdr") {
            BrowseSectionHeader(
                stringResource(R.string.explorer_windows),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(windows, key = { "w-${it.id}" }) { window ->
            val source = windowSourceName(window, roots, smb, webDav)
            val savedAlready = SavedExplorerPaths.contains(window.kind, window.sourceId, window.relativePath)
            ExplorerPathRow(
                title = windowRowTitle(window, source),
                subtitle = windowSubtitle(source, window.relativePath),
                active = window.id == activeId,
                onClick = {
                    val shown = ExplorerWindows.activate(window.id) ?: return@ExplorerPathRow
                    showWindow(navigator, shown, currentDestination, browserSourceId)
                    onNavigated()
                },
                trailing = {
                    IconButton(
                        onClick = {
                            SavedExplorerPaths.remember(window.kind, window.sourceId, window.relativePath)
                        },
                    ) {
                        Icon(
                            if (savedAlready) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = stringResource(R.string.explorer_save_path),
                        )
                    }
                    IconButton(
                        onClick = {
                            when (val result = ExplorerWindows.close(window.id)) {
                                ExplorerWindows.CloseResult.Unchanged -> Unit
                                ExplorerWindows.CloseResult.NoneLeft -> {
                                    if (viewingBrowser) {
                                        navigator.popBackStack()
                                        onNavigated()
                                    }
                                }
                                is ExplorerWindows.CloseResult.Switched -> {
                                    if (viewingBrowser) {
                                        showWindow(navigator, result.window, currentDestination, browserSourceId)
                                        onNavigated()
                                    }
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.explorer_close_window),
                        )
                    }
                },
            )
        }
        item(key = "saved-hdr") {
            BrowseSectionHeader(
                stringResource(R.string.explorer_saved_paths),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(saved, key = { "s-${it.key}" }) { item ->
            ExplorerPathRow(
                title = item.title,
                subtitle = windowSubtitle(item.sourceName, item.relativePath),
                active = false,
                onClick = {
                    openSaved(navigator, item, roots)
                    onNavigated()
                },
                trailing = {
                    IconButton(onClick = { SavedExplorerPaths.forget(item.key) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.explorer_delete_saved),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ExplorerFavoritesGrid(
    navigator: DestinationsNavigator,
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
    val gridState = remember { LazyGridState() }
    FastScrollLazyVerticalGrid(
        columns = GalleryGridDefaults.columns(),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = GalleryGridDefaults.contentPadding(androidx.compose.foundation.layout.PaddingValues(0.dp)),
        verticalArrangement = GalleryGridDefaults.spacedBy(),
        horizontalArrangement = GalleryGridDefaults.spacedBy(),
    ) {
        item(key = "fav-hdr", span = { GridItemSpan(maxLineSpan) }) {
            BrowseSectionHeader(
                stringResource(R.string.browse_favorites),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        items(favorites, key = { "fav-${it.key}" }) { fav ->
            FavoriteSourceGridCell(
                fav = fav,
                onClick = {
                    openFavorite(navigator, fav, roots)
                    onNavigated()
                },
                onLongClick = { toggleFavorite(fav) },
            )
        }
        if (smb.isNotEmpty() || webDav.isNotEmpty()) {
            item(key = "src-net", span = { GridItemSpan(maxLineSpan) }) {
                BrowseSectionHeader(
                    stringResource(R.string.network),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            items(smb, key = { "smb-${it.id}" }) { source ->
                val favorited = BrowseFavorites.smbKey(source.id) in favoriteKeys
                BrowseRootCard(
                    title = source.displayName,
                    subtitle = smbSubtitle(source),
                    favorited = favorited,
                    icon = { Icon(Icons.Default.Lan, contentDescription = null) },
                    onClick = {
                        openSmbRoot(navigator, source)
                        onNavigated()
                    },
                    onLongClick = { BrowseFavorites.toggleSmb(source.id) },
                )
            }
            items(webDav, key = { "dav-${it.id}" }) { source ->
                val favorited = BrowseFavorites.webDavKey(source.id) in favoriteKeys
                BrowseRootCard(
                    title = source.displayName,
                    subtitle = webDavSubtitle(source),
                    favorited = favorited,
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
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
                BrowseSectionHeader(
                    stringResource(R.string.folder),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            items(roots, key = { "root-${it.id}" }) { root ->
                val favorited = BrowseFavorites.localKey(root.id) in favoriteKeys
                BrowseRootCard(
                    title = root.displayName.safFolderLabel(),
                    subtitle = stringResource(
                        if (root.isLibraryRole) R.string.library else R.string.folder,
                    ),
                    favorited = favorited,
                    icon = {
                        Icon(
                            if (root.isLibraryRole) {
                                Icons.AutoMirrored.Filled.LibraryBooks
                            } else {
                                Icons.Default.Folder
                            },
                            contentDescription = null,
                        )
                    },
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
private fun ExplorerPathRow(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    BrowseFolderListItem(
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(BrowseListLeadingIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        },
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick),
    )
}

private fun showWindow(
    navigator: DestinationsNavigator,
    window: ExplorerWindows.Window,
    currentDestination: Any?,
    browserSourceId: Long?,
) {
    val same = when (window.kind) {
        ExplorerWindows.Kind.Local -> currentDestination == FolderBrowserScreenDestination
        ExplorerWindows.Kind.Smb ->
            currentDestination == SmbBrowserScreenDestination && browserSourceId == window.sourceId
        ExplorerWindows.Kind.WebDav ->
            currentDestination == WebDavBrowserScreenDestination && browserSourceId == window.sourceId
    }
    if (same) return
    with(navigator) {
        when (window.kind) {
            ExplorerWindows.Kind.Local -> navigate(
                FolderBrowserScreenDestination(
                    fromHistory = window.fromHistory,
                    fromLibrary = window.fromLibrary,
                ),
            ) { launchSingleTop = true }
            ExplorerWindows.Kind.Smb -> navigate(
                SmbBrowserScreenDestination(
                    sourceId = window.sourceId,
                    initialRelativePath = window.relativePath,
                    fromHistory = window.fromHistory,
                    fromLibrary = window.fromLibrary,
                ),
            ) { launchSingleTop = true }
            ExplorerWindows.Kind.WebDav -> navigate(
                WebDavBrowserScreenDestination(
                    sourceId = window.sourceId,
                    initialRelativePath = window.relativePath,
                    fromHistory = window.fromHistory,
                    fromLibrary = window.fromLibrary,
                ),
            ) { launchSingleTop = true }
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
                )
            }
            ExplorerWindows.Kind.Smb -> openSmbBrowseDir(item.sourceId, item.relativePath)
            ExplorerWindows.Kind.WebDav -> openWebDavBrowseDir(item.sourceId, item.relativePath)
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
                    fromLibrary = true,
                )
            }
            is FavoriteBrowseSource.Smb -> openSmbBrowseDir(fav.source.id, "", fromLibrary = true)
            is FavoriteBrowseSource.WebDav -> openWebDavBrowseDir(fav.source.id, "", fromLibrary = true)
            is FavoriteBrowseSource.LocalFolder -> {
                val rootPath = LocalLibrary.rootPath(fav.root) ?: return
                openLocalBrowseDir(
                    rootId = fav.root.id,
                    rootDisplayName = fav.root.displayName,
                    rootPath = rootPath,
                    relativePath = fav.relativePath,
                    preferMediaStore = fav.root.prefersMediaStore,
                    fromLibrary = true,
                )
            }
            is FavoriteBrowseSource.SmbFolder ->
                openSmbBrowseDir(fav.source.id, fav.relativePath, fromLibrary = true)
            is FavoriteBrowseSource.WebDavFolder ->
                openWebDavBrowseDir(fav.source.id, fav.relativePath, fromLibrary = true)
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
            fromLibrary = true,
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
    ExplorerWindows.finishLocalSpawn(root.displayName.safFolderLabel())
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
    ExplorerWindows.finishSmbSpawn(source.id, source.displayName)
    navigator.navigate(SmbBrowserScreenDestination(source.id, "")) { launchSingleTop = true }
}

private fun openWebDavRoot(
    navigator: DestinationsNavigator,
    source: com.ehviewer.core.database.model.WebDavSourceEntity,
) {
    ExplorerWindows.prepareSpawn()
    BrowseSession.setWebDavSegments(source.id, emptyList())
    BrowseSession.setWebDavPhotoGrid(source.id, null)
    BrowseSession.setWebDavExitToOrigin(source.id, false)
    ExplorerWindows.finishWebDavSpawn(source.id, source.displayName)
    navigator.navigate(WebDavBrowserScreenDestination(source.id, "")) { launchSingleTop = true }
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
