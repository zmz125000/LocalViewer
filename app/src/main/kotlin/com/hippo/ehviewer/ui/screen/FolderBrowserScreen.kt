package com.hippo.ehviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseEntry
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.listLocalDirectory
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.ui.LocalShowNavShortcutFab
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.BrowseScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.main.BrowseArchiveGalleryRow
import com.hippo.ehviewer.ui.main.BrowseArchiveGridItem
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowseDirectoryGridItem
import com.hippo.ehviewer.ui.main.BrowseDirectoryRow
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryGridItem
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryRow
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.FolderBrowserScreen(
    navigator: DestinationsNavigator,
    /** When opened from History, show a FAB to jump straight back (skip path climb). */
    fromHistory: Boolean = false,
) = Screen(navigator) {
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    // Session-scoped stack survives reader navigation (unlike remember {}).
    // When opened from Browse with a pre-set stack, start inside that root (no root picker).
    var stack by remember {
        mutableStateOf(BrowseSession.localStack)
    }
    fun updateStack(newStack: List<BrowseSession.LocalFrame>) {
        stack = newStack
        BrowseSession.localStack = newStack
    }

    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }

    /** Path the current [entries] belong to — avoids showing the wrong dir during reload. */
    var listedPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listMode by Settings.listMode.collectAsState()
    val useGrid = listMode == 1

    val current = stack.lastOrNull()
    val currentPath = current?.path
    val title = current?.title ?: stringResource(R.string.folder)
    // Scroll down hides the top bar; scroll up brings it back (enterAlways).
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // FAB tracks the same enterAlways state (hide when bar collapses, show when it reappears).
    val showScrollFab by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction < 0.5f }
    }

    // Show the bar again when entering a different folder.
    LaunchedEffect(currentPath) {
        scrollBehavior.state.heightOffset = 0f
    }

    suspend fun reload(force: Boolean = false) {
        val frame = stack.lastOrNull()
        if (frame == null) {
            entries = emptyList()
            listedPath = null
            error = null
            return
        }
        val targetPath = frame.path
        loading = true
        error = null
        // Drop stale rows so we never paint child content under a parent path (or vice versa).
        // Also removes the Lazy list from composition so its DisposableEffect can save scroll
        // for the *leaving* path (not the destination).
        if (listedPath != targetPath) {
            entries = emptyList()
        }
        try {
            val result = withIOContext {
                if (force) BrowseSession.invalidateLocalListing(frame.path)
                listLocalDirectory(frame.path.toPath(), useCache = !force)
            }
            if (stack.lastOrNull()?.path != targetPath) return
            entries = result
            listedPath = targetPath
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (stack.lastOrNull()?.path != targetPath) return
            error = e.message
            entries = emptyList()
            listedPath = targetPath
        } finally {
            if (stack.lastOrNull()?.path == targetPath) {
                loading = false
            }
        }
    }

    LaunchedEffect(stack) { reload(force = false) }

    fun enterRoot(root: LibraryRootEntity) {
        val path = LocalLibrary.rootPath(root) ?: return
        updateStack(
            listOf(
                BrowseSession.LocalFrame(
                    rootId = root.id,
                    path = path.toString(),
                    title = root.displayName,
                    relativePath = "",
                ),
            ),
        )
    }

    fun enterDir(entry: BrowseEntry.Directory) {
        val frame = stack.lastOrNull() ?: return
        val rel = if (frame.relativePath.isEmpty()) entry.name else "${frame.relativePath}/${entry.name}"
        updateStack(
            stack + BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = entry.path.toString(),
                title = entry.name,
                relativePath = rel,
            ),
        )
    }

    fun goUp() {
        if (stack.size > 1) {
            updateStack(stack.dropLast(1))
        } else {
            // Leave folder browser back to Browse hub
            updateStack(emptyList())
            navigator.popBackStack()
        }
    }

    BackHandler { goUp() }

    fun openFolderGallery(entry: BrowseEntry.FolderGallery) {
        val frame = stack.lastOrNull() ?: return
        // Playlist = gallery/archive rows in this browse list (lazy galleries), not only
        // path-parent siblings.
        ReaderGalleryPlaylist.setFromLocalBrowse(
            rootId = frame.rootId,
            parentPath = frame.path,
            parentRelative = frame.relativePath,
            entries = entries,
        )
        val rel = when {
            frame.relativePath.isEmpty() && entry.path.toString() == frame.path -> ""
            frame.relativePath.isEmpty() -> entry.name
            entry.path.toString() == frame.path -> frame.relativePath
            else -> "${frame.relativePath}/${entry.name}"
        }
        val gid = stableGalleryId(frame.rootId, rel.ifEmpty { "." })
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_GALLERY_TOKEN,
            title = entry.name,
            pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = entry.coverPath?.toString(),
        )
        launchIO {
            // Progress FK for reader; History lists the browse folder path, not the leaf gallery.
            LocalHistory.ensureGalleryForProgress(info)
            LocalHistory.recordLocalBrowseFolder(
                rootId = frame.rootId,
                relativePath = rel,
                title = entry.name,
                coverPath = entry.coverPath?.toString(),
                pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            )
        }
        navToLocalFolderReader(entry.path.toString(), info)
    }

    fun openArchive(entry: BrowseEntry.ArchiveGallery) {
        val frame = stack.lastOrNull()
        if (frame != null) {
            ReaderGalleryPlaylist.setFromLocalBrowse(
                rootId = frame.rootId,
                parentPath = frame.path,
                parentRelative = frame.relativePath,
                entries = entries,
            )
        }
        navToReader(entry.path.toString())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                navigationIcon = {
                    IconButton(onClick = { goUp() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            Settings.listMode.value = if (listMode == 0) 1 else 0
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        val icon = if (listMode == 1) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList
                        val desc = if (listMode == 0) {
                            stringResource(R.string.settings_eh_list_mode_thumb)
                        } else {
                            stringResource(R.string.settings_eh_list_mode_detail)
                        }
                        Icon(imageVector = icon, contentDescription = desc)
                    }
                    IconButton(
                        onClick = {
                            launch {
                                refreshing = true
                                reload(force = true)
                                refreshing = false
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // Compact phones without persistent main nav: shortcut FAB.
            // Tablets (rail) and Settings → Keep main navigation: re-tap tab instead.
            // Visibility follows enterAlways top-bar scroll (same collapsedFraction).
            if (LocalShowNavShortcutFab.current) {
                AnimatedVisibility(
                    visible = showScrollFab,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    if (fromHistory) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (!navigator.popBackStack(HistoryScreenDestination, inclusive = false)) {
                                    navigator.navigate(HistoryScreenDestination) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = {
                                Icon(Icons.Default.History, contentDescription = null)
                            },
                            text = { Text(stringResource(R.string.back_to_history)) },
                        )
                    } else {
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (!navigator.popBackStack(BrowseScreenDestination, inclusive = false)) {
                                    navigator.navigate(BrowseScreenDestination) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = {
                                Icon(Icons.Default.Explore, contentDescription = null)
                            },
                            text = { Text(stringResource(R.string.back_to_browse)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing || loading,
            onRefresh = {
                launch {
                    refreshing = true
                    reload(force = true)
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                stack.isEmpty() -> {
                    // Should open from Browse with a pre-selected root; show fallback if not
                    if (roots.isEmpty()) {
                        BrowseEmptyHint(stringResource(R.string.folder_no_roots))
                    } else {
                        FastScrollLazyColumn(Modifier.fillMaxSize()) {
                            items(roots, key = { it.id }) { root ->
                                BrowseDirectoryRow(
                                    name = root.displayName,
                                    onClick = { enterRoot(root) },
                                )
                            }
                        }
                    }
                }
                loading && (entries.isEmpty() || listedPath != currentPath) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                error != null && entries.isEmpty() -> {
                    BrowseEmptyHint(error!!)
                }
                entries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    // List only composes when this path's entries are ready. State is keyed by
                    // path+mode so parent/child never share one LazyList scroll index.
                    val pathKey = listedPath ?: currentPath!!
                    val dirs = entries.filterIsInstance<BrowseEntry.Directory>()
                    val galleries = entries.filter { it !is BrowseEntry.Directory }
                    if (useGrid) {
                        val gridState = rememberBrowseGridState(pathKey, listMode)
                        val gridSpacing = GalleryGridDefaults.spacedBy()
                        FastScrollLazyVerticalGrid(
                            columns = GalleryGridDefaults.columns(),
                            state = gridState,
                            modifier = Modifier
                                .nestedScroll(scrollBehavior.nestedScrollConnection)
                                .fillMaxSize(),
                            contentPadding = GalleryGridDefaults.contentPadding(),
                            horizontalArrangement = gridSpacing,
                            verticalArrangement = gridSpacing,
                        ) {
                            if (dirs.isNotEmpty()) {
                                item(
                                    key = "hdr-dirs",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(stringResource(R.string.browse_directories))
                                }
                                items(dirs, key = { "d-${it.path}" }) { dir ->
                                    BrowseDirectoryGridItem(name = dir.name, onClick = { enterDir(dir) })
                                }
                            }
                            if (galleries.isNotEmpty()) {
                                item(
                                    key = "hdr-gal",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(stringResource(R.string.browse_galleries))
                                }
                                items(
                                    galleries,
                                    key = { entry ->
                                        when (entry) {
                                            // path distinguishes self-as-gallery vs child gallery with same name
                                            is BrowseEntry.FolderGallery -> "g-${entry.path}"
                                            is BrowseEntry.ArchiveGallery -> "a-${entry.path}"
                                            is BrowseEntry.Directory -> "d-${entry.path}"
                                        }
                                    },
                                ) { entry ->
                                    when (entry) {
                                        is BrowseEntry.FolderGallery -> BrowseFolderGalleryGridItem(
                                            name = entry.name,
                                            pageCount = entry.pageCount,
                                            pageCountCapped = entry.pageCountCapped,
                                            cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                            onClick = { openFolderGallery(entry) },
                                        )
                                        is BrowseEntry.ArchiveGallery -> BrowseArchiveGridItem(
                                            name = entry.name,
                                            onClick = { openArchive(entry) },
                                        )
                                        is BrowseEntry.Directory -> Unit
                                    }
                                }
                            }
                        }
                    } else {
                        val listState = rememberBrowseListState(pathKey, listMode)
                        FastScrollLazyColumn(
                            state = listState,
                            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).fillMaxSize(),
                        ) {
                            if (dirs.isNotEmpty()) {
                                item(key = "hdr-dirs") {
                                    BrowseSectionHeader(stringResource(R.string.browse_directories))
                                }
                                items(dirs, key = { "d-${it.path}" }) { dir ->
                                    BrowseDirectoryRow(name = dir.name, onClick = { enterDir(dir) })
                                }
                            }
                            if (galleries.isNotEmpty()) {
                                item(key = "hdr-gal") {
                                    BrowseSectionHeader(stringResource(R.string.browse_galleries))
                                }
                                items(
                                    galleries,
                                    key = { entry ->
                                        when (entry) {
                                            is BrowseEntry.FolderGallery -> "g-${entry.path}"
                                            is BrowseEntry.ArchiveGallery -> "a-${entry.path}"
                                            is BrowseEntry.Directory -> "d-${entry.path}"
                                        }
                                    },
                                ) { entry ->
                                    when (entry) {
                                        is BrowseEntry.FolderGallery -> BrowseFolderGalleryRow(
                                            name = entry.name,
                                            pageCount = entry.pageCount,
                                            pageCountCapped = entry.pageCountCapped,
                                            cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                            onClick = { openFolderGallery(entry) },
                                        )
                                        is BrowseEntry.ArchiveGallery -> BrowseArchiveGalleryRow(
                                            name = entry.name,
                                            onClick = { openArchive(entry) },
                                        )
                                        is BrowseEntry.Directory -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Per-directory list scroll. Keyed by [pathKey]+[listMode] so parent/child never share one
 * LazyListState. Saved on dispose (path change unmounts the list while loading the next dir).
 * Reader stays under the back stack so this state is kept without re-save/restore.
 */
@Composable
internal fun rememberBrowseListState(pathKey: String, listMode: Int): LazyListState {
    val state = remember(pathKey, listMode) {
        val saved = BrowseSession.localScroll(pathKey, listMode)
        LazyListState(saved?.index ?: 0, saved?.offset ?: 0)
    }
    DisposableEffect(pathKey, listMode, state) {
        onDispose {
            BrowseSession.saveLocalScroll(
                pathKey,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                listMode,
            )
        }
    }
    // Re-apply after first layout — constructor index can be clamped when items are not ready yet.
    LaunchedEffect(pathKey, listMode, state) {
        val saved = BrowseSession.localScroll(pathKey, listMode) ?: return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        val max = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        state.scrollToItem(saved.index.coerceIn(0, max), saved.offset)
    }
    return state
}

@Composable
internal fun rememberBrowseGridState(pathKey: String, listMode: Int): LazyGridState {
    val state = remember(pathKey, listMode) {
        val saved = BrowseSession.localScroll(pathKey, listMode)
        LazyGridState(saved?.index ?: 0, saved?.offset ?: 0)
    }
    DisposableEffect(pathKey, listMode, state) {
        onDispose {
            BrowseSession.saveLocalScroll(
                pathKey,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                listMode,
            )
        }
    }
    LaunchedEffect(pathKey, listMode, state) {
        val saved = BrowseSession.localScroll(pathKey, listMode) ?: return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        val max = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        state.scrollToItem(saved.index.coerceIn(0, max), saved.offset)
    }
    return state
}

/** SMB variant — same mechanics, separate session map. */
@Composable
internal fun rememberSmbBrowseListState(
    sourceId: Long,
    relativeDir: String,
    listMode: Int,
): LazyListState {
    val pathKey = "$sourceId|$relativeDir"
    val state = remember(pathKey, listMode) {
        val saved = BrowseSession.smbScroll(sourceId, relativeDir, listMode)
        LazyListState(saved?.index ?: 0, saved?.offset ?: 0)
    }
    DisposableEffect(pathKey, listMode, state) {
        onDispose {
            BrowseSession.saveSmbScroll(
                sourceId,
                relativeDir,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                listMode,
            )
        }
    }
    LaunchedEffect(pathKey, listMode, state) {
        val saved = BrowseSession.smbScroll(sourceId, relativeDir, listMode) ?: return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        val max = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        state.scrollToItem(saved.index.coerceIn(0, max), saved.offset)
    }
    return state
}

@Composable
internal fun rememberSmbBrowseGridState(
    sourceId: Long,
    relativeDir: String,
    listMode: Int,
): LazyGridState {
    val pathKey = "$sourceId|$relativeDir"
    val state = remember(pathKey, listMode) {
        val saved = BrowseSession.smbScroll(sourceId, relativeDir, listMode)
        LazyGridState(saved?.index ?: 0, saved?.offset ?: 0)
    }
    DisposableEffect(pathKey, listMode, state) {
        onDispose {
            BrowseSession.saveSmbScroll(
                sourceId,
                relativeDir,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                listMode,
            )
        }
    }
    LaunchedEffect(pathKey, listMode, state) {
        val saved = BrowseSession.smbScroll(sourceId, relativeDir, listMode) ?: return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        val max = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        state.scrollToItem(saved.index.coerceIn(0, max), saved.offset)
    }
    return state
}
