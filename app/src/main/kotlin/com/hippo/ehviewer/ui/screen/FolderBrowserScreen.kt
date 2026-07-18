package com.hippo.ehviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.listLocalDirectory
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseArchiveGalleryRow
import com.hippo.ehviewer.ui.main.BrowseArchiveGridItem
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowseDirectoryGridItem
import com.hippo.ehviewer.ui.main.BrowseDirectoryRow
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryGridItem
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryRow
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
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
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val listMode by Settings.listMode.collectAsState()
    val useGrid = listMode == 1

    val current = stack.lastOrNull()
    val currentPath = current?.path
    val title = current?.title ?: stringResource(R.string.folder)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun saveCurrentScroll() {
        val path = currentPath ?: return
        if (useGrid) {
            BrowseSession.saveLocalScroll(
                path,
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                listMode = 1,
            )
        } else {
            BrowseSession.saveLocalScroll(
                path,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listMode = 0,
            )
        }
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
        if (listedPath != targetPath) {
            entries = emptyList()
        }
        runCatching {
            withIOContext {
                if (force) BrowseSession.invalidateLocalListing(frame.path)
                listLocalDirectory(frame.path.toPath(), useCache = !force)
            }
        }.onSuccess {
            entries = it
            listedPath = targetPath
        }.onFailure {
            error = it.message
            entries = emptyList()
            listedPath = targetPath
        }
        loading = false
    }

    LaunchedEffect(stack) { reload(force = false) }

    // Persist scroll when leaving a directory (enter subfolder, go up, open reader, leave screen).
    DisposableEffect(currentPath) {
        onDispose { saveCurrentScroll() }
    }

    // Restore once per path+mode visit (exact scroll, else explorer-style anchor for list).
    var restoredKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentPath, listedPath, entries, listMode) {
        val path = currentPath ?: return@LaunchedEffect
        if (listedPath != path || entries.isEmpty()) return@LaunchedEffect
        val visitKey = BrowseSession.scrollModeKey(path, listMode)
        if (restoredKey == visitKey) return@LaunchedEffect
        val dirs = entries.filterIsInstance<BrowseEntry.Directory>()
        val saved = BrowseSession.localScroll(path, listMode)
        val maxIndex = (browseListItemCount(dirs.size, entries.size - dirs.size) - 1).coerceAtLeast(0)
        if (useGrid) {
            if (saved != null) {
                gridState.scrollToItem(saved.index.coerceIn(0, maxIndex), saved.offset)
            } else {
                gridState.scrollToItem(0)
            }
        } else {
            when {
                saved != null -> {
                    listState.scrollToItem(saved.index.coerceIn(0, maxIndex), saved.offset)
                }
                else -> {
                    val anchor = BrowseSession.takeLocalScrollAnchor(path)
                    val index = anchor?.let { browseDirectoryRowIndex(dirs.map { d -> d.name }, it) }
                    listState.scrollToItem(index ?: 0)
                }
            }
        }
        restoredKey = visitKey
    }

    fun enterRoot(root: LibraryRootEntity) {
        saveCurrentScroll()
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
        saveCurrentScroll()
        BrowseSession.setLocalScrollAnchor(frame.path, entry.name)
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
            val leaving = stack.last()
            val parent = stack.dropLast(1)
            saveCurrentScroll()
            BrowseSession.setLocalScrollAnchor(parent.last().path, leaving.title)
            updateStack(parent)
        } else {
            // Leave folder browser back to Browse hub
            saveCurrentScroll()
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
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { goUp() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            saveCurrentScroll()
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
            if (fromHistory) {
                ExtendedFloatingActionButton(
                    onClick = {
                        saveCurrentScroll()
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
                    val dirs = entries.filterIsInstance<BrowseEntry.Directory>()
                    val galleries = entries.filter { it !is BrowseEntry.Directory }
                    if (useGrid) {
                        FastScrollLazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier
                                .nestedScroll(scrollBehavior.nestedScrollConnection)
                                .fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                items(galleries, key = { "g-${it.name}-${it.hashCode()}" }) { entry ->
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
                                items(galleries, key = { "g-${it.name}-${it.hashCode()}" }) { entry ->
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

/** LazyColumn item count for browse lists (optional section headers). */
internal fun browseListItemCount(dirCount: Int, galleryCount: Int): Int {
    var n = 0
    if (dirCount > 0) n += 1 + dirCount
    if (galleryCount > 0) n += 1 + galleryCount
    return n
}

/** Index of a directory row (after the directories section header). */
internal fun browseDirectoryRowIndex(dirNames: List<String>, name: String): Int? {
    val i = dirNames.indexOf(name)
    if (i < 0) return null
    return 1 + i
}
