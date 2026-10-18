package com.hippo.ehviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.fork.SwipeToDismissBox
import androidx.compose.material3.fork.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.big.History
import com.ehviewer.core.ui.util.rememberInVM
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.HistoryHidePersist
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.libraryBrowseRelative
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.ExplorerGestureEnabled
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.HistoryDirectoryGridItem
import com.hippo.ehviewer.ui.main.HistoryGridItem
import com.hippo.ehviewer.ui.main.HistoryListItem
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.navToSmbStreamArchiveReader
import com.hippo.ehviewer.ui.navToWebDavStreamArchiveReader
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.hippo.ehviewer.ui.openLocalFolderPhotoGrid
import com.hippo.ehviewer.ui.openSmbBrowseDir
import com.hippo.ehviewer.ui.openSmbFolderPhotoGrid
import com.hippo.ehviewer.ui.openWebDavBrowseDir
import com.hippo.ehviewer.ui.openWebDavFolderPhotoGrid
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tarsin.snackbar
import moe.tarsin.string

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.HistoryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val context = LocalContext.current
    val title = stringResource(id = R.string.history)
    val hint = stringResource(R.string.search_bar_hint, title)
    val animateItems by Settings.animateItems.collectAsState()

    var searchFocused by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    // Live filter text from the search field (updates as the user types).
    var keyword by rememberSaveable { mutableStateOf("") }

    ExplorerGestureEnabled(!searchFocused)

    val density = LocalDensity.current
    // Hide-from-history is applied before this list is published, so the grid never composes those rows.
    // The tab reads the RAM snapshot first. Keyword and Media/Documents filtering run
    // off the main thread when that snapshot is stale.
    var historyLoaded by rememberInVM { mutableStateOf(false) }
    val visibleHistory by rememberInVM {
        mutableStateOf(emptyList<GalleryEntity>()).also { state ->
            viewModelScope.launch {
                combine(
                    EhDB.historyListFlow,
                    Settings.historyHideFolders.valueFlow(),
                ) { rows, stored -> rows to stored }
                    .collect { (rows, stored) ->
                        val shown = HistoryHidePersist.withoutHiddenFolders(rows, stored)
                        HistoryListMemory.store(shown)
                        state.value = shown
                        historyLoaded = true
                    }
            }
        }
    }
    val historySectionPref by Settings.historySection.collectAsState()
    val historySection = HistorySection.fromPref(historySectionPref)
    val ebookAsGallery by Settings.showEbookFilesAsGallery.collectAsState()
    val favoriteKeys by Settings.favoriteBrowseSources.collectAsState()
    val filterQuery = keyword.trim()
    val historySource = if (historyLoaded || visibleHistory.isNotEmpty()) {
        visibleHistory
    } else {
        HistoryListMemory.source()
    }
    val historyKey = HistoryListKey(
        keyword = filterQuery,
        section = historySection,
        ebookAsGallery = ebookAsGallery,
    )
    val immediateHistory = HistoryListMemory.display(historySource, historyKey)
    var allDirectoryItems by remember { mutableStateOf(immediateHistory?.directories ?: emptyList()) }
    var allFileItems by remember { mutableStateOf(immediateHistory?.files ?: emptyList()) }
    var historyItems by remember { mutableStateOf(immediateHistory?.shownFiles ?: emptyList()) }
    var preparingHistory by remember { mutableStateOf(immediateHistory == null && historySource.isNotEmpty()) }
    LaunchedEffect(historySource, historyKey) {
        if (historySource.isEmpty() && !historyLoaded && !HistoryListMemory.loaded) return@LaunchedEffect
        val ready = HistoryListMemory.peek(historySource, historyKey)
        if (ready != null) {
            allDirectoryItems = ready.directories
            allFileItems = ready.files
            historyItems = ready.shownFiles
            preparingHistory = false
            return@LaunchedEffect
        }
        val source = historySource
        val built = withContext(Dispatchers.Default) {
            HistoryListMemory.prepare(source, historyKey)
        }
        allDirectoryItems = built.directories
        allFileItems = built.files
        historyItems = built.shownFiles
        preparingHistory = false
    }
    val hasFileItems = allFileItems.isNotEmpty()

    val listMode by Settings.listMode.collectAsState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)
    val gridColumnCount = GalleryGridDefaults.columnCount()
    // Collapsed: list 5 rows (× columns on wide) / grid max two rows. Expanded: full dir pin list.
    var directoriesExpanded by rememberSaveable { mutableStateOf(false) }
    val listColumnCount = GalleryGridDefaults.listColumnCount()
    val directoryCollapsedLimit = remember(listMode, gridColumnCount, listColumnCount) {
        if (listMode == 0) {
            HISTORY_DIRECTORY_LIST_LIMIT * listColumnCount
        } else {
            gridColumnCount * HISTORY_DIRECTORY_GRID_ROWS
        }
    }
    val canExpandDirectories = allDirectoryItems.size > directoryCollapsedLimit
    // Drop expanded state when there is nothing left to expand (filter / fewer pins).
    LaunchedEffect(canExpandDirectories) {
        if (!canExpandDirectories) directoriesExpanded = false
    }
    val directoryItems = remember(allDirectoryItems, directoriesExpanded, directoryCollapsedLimit) {
        if (directoriesExpanded) allDirectoryItems else allDirectoryItems.take(directoryCollapsedLimit)
    }
    // Back collapses the dir strip before leaving History.
    BackHandler(enabled = directoriesExpanded) {
        directoriesExpanded = false
    }

    fun openEntry(info: GalleryEntity) {
        openHistoryRecord(context, info, filterQuery)
    }

    fun deleteEntry(info: GalleryEntity) {
        launch {
            EhDB.deleteHistoryInfo(info)
        }
    }

    fun openFolderFromHistory(info: GalleryEntity) {
        launch {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalBrowseFolder,
                is LocalHistoryTarget.SmbBrowseFolder,
                is LocalHistoryTarget.WebDavBrowseFolder,
                -> openEntry(info)
                is LocalHistoryTarget.LocalFolderGallery -> {
                    val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openLocalBrowseDir(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = target.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.SmbFolderGallery -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openSmbBrowseDir(
                        sourceId = source.id,
                        remoteDir = target.remoteDir.trim('/'),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.WebDavFolderGallery -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openWebDavBrowseDir(
                        sourceId = source.id,
                        remoteDir = target.remoteDir.trim('/'),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.LocalArchive -> {
                    val parent = withIOContext { LocalLibrary.resolveArchiveBrowseParent(target.path) }
                    if (parent == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    val prefers = withIOContext {
                        LocalLibrary.loadRoot(parent.rootId)?.prefersMediaStore
                    } ?: false
                    openLocalBrowseDir(
                        rootId = parent.rootId,
                        rootDisplayName = parent.rootDisplayName,
                        rootPath = parent.rootPath,
                        relativePath = parent.parentRelativePath,
                        preferMediaStore = prefers,
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.LocalFile -> {
                    val parent = withIOContext { LocalLibrary.resolveArchiveBrowseParent(target.path) }
                    if (parent == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    val prefers = withIOContext {
                        LocalLibrary.loadRoot(parent.rootId)?.prefersMediaStore
                    } ?: false
                    openLocalBrowseDir(
                        rootId = parent.rootId,
                        rootDisplayName = parent.rootDisplayName,
                        rootPath = parent.rootPath,
                        relativePath = parent.parentRelativePath,
                        preferMediaStore = prefers,
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.SmbStreamArchive -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openSmbBrowseDir(
                        sourceId = source.id,
                        remoteDir = parentRelativeOfFile(target.remotePath),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.SmbFile -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openSmbBrowseDir(
                        sourceId = source.id,
                        remoteDir = parentRelativeOfFile(target.remotePath),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.WebDavStreamArchive -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openWebDavBrowseDir(
                        sourceId = source.id,
                        remoteDir = parentRelativeOfFile(target.remotePath),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.WebDavFile -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openWebDavBrowseDir(
                        sourceId = source.id,
                        remoteDir = parentRelativeOfFile(target.remotePath),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = withIOContext { LocalLibrary.loadGallery(target.galleryId) }
                    if (local == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    val rel = if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        parentRelativeOfFile(local.relativePath)
                    } else {
                        libraryBrowseRelative(local.relativePath)
                    }
                    openLocalBrowseDir(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = rel,
                        preferMediaStore = root.prefersMediaStore,
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.Orphan -> {
                    snackbar(string(R.string.browse_action_not_supported))
                }
            }
        }
    }

    fun openPhotoGridFromHistory(info: GalleryEntity) {
        launch {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalFolderGallery -> {
                    val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    withIOContext { EhDB.putHistoryInfo(info) }
                    openLocalFolderPhotoGrid(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = target.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                        title = info.title ?: target.relativePath.substringAfterLast('/'),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.SmbFolderGallery -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openSmbFolderPhotoGrid(
                        sourceId = source.id,
                        remoteDir = target.remoteDir.trim('/'),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.WebDavFolderGallery -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    openWebDavFolderPhotoGrid(
                        sourceId = source.id,
                        remoteDir = target.remoteDir.trim('/'),
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = withIOContext { LocalLibrary.loadGallery(target.galleryId) }
                    if (local == null || local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        snackbar(string(R.string.browse_action_not_supported))
                        return@launch
                    }
                    val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    withIOContext { EhDB.putHistoryInfo(info) }
                    openLocalFolderPhotoGrid(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = local.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                        title = local.title,
                        fromHistory = true,
                    )
                }
                else -> snackbar(string(R.string.browse_action_not_supported))
            }
        }
    }

    fun imageReaderFromHistory(info: GalleryEntity) {
        launch {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalArchive -> navToReader(target.path, skipPdfPrimary = true)
                is LocalHistoryTarget.SmbStreamArchive -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    navToSmbStreamArchiveReader(
                        source.id,
                        target.remotePath.trim('/'),
                        skipPdfPrimary = true,
                    )
                }
                is LocalHistoryTarget.WebDavStreamArchive -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    navToWebDavStreamArchiveReader(
                        source.id,
                        target.remotePath.trim('/'),
                        skipPdfPrimary = true,
                    )
                }
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = withIOContext { LocalLibrary.loadGallery(target.galleryId) }
                    if (local == null) {
                        snackbar(string(R.string.history_unavailable))
                        return@launch
                    }
                    navToReader(local.contentPath, skipPdfPrimary = true)
                }
                else -> openEntry(info)
            }
        }
    }

    fun overflowFor(info: GalleryEntity) = historyOverflowActions(
        context = context,
        info = info,
        favoriteKeys = favoriteKeys,
        openEntry = { openEntry(info) },
        openPhotoGrid = { openPhotoGridFromHistory(info) },
        openFolder = { openFolderFromHistory(info) },
        imageReader = { imageReaderFromHistory(info) },
    )

    SearchBarScreen(
        onFilterChange = { keyword = it },
        onFocusChange = { searchFocused = it },
        title = title,
        searchFieldHint = hint,
        searchBarOffsetY = { searchBarOffsetY },
        leadingIcon = {
            // Simple list ↔ grid toggle (Library has its own sort/view menu).
            IconButton(
                onClick = { Settings.listMode.value = if (listMode == 0) 1 else 0 },
                shapes = IconButtonDefaults.shapes(),
            ) {
                val icon = if (listMode == 0) Icons.AutoMirrored.Default.ViewList else Icons.Default.GridView
                val desc = if (listMode == 0) {
                    stringResource(R.string.settings_eh_list_mode_thumb)
                } else {
                    stringResource(R.string.settings_eh_list_mode_detail)
                }
                Icon(imageVector = icon, contentDescription = desc)
            }
        },
        trailingIcon = {
            IconButton(
                onClick = {
                    launch {
                        awaitConfirmationOrCancel(
                            confirmText = R.string.clear_all,
                            text = { Text(text = stringResource(id = R.string.clear_all_history)) },
                        )
                        EhDB.clearHistoryInfo()
                    }
                },
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(imageVector = Icons.Default.ClearAll, contentDescription = null)
            }
        },
    ) { paddingValues ->
        val searchBarConnection = remember {
            val topPaddingPx = with(density) { paddingValues.calculateTopPadding().roundToPx() }
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = -consumed.y
                    searchBarOffsetY = (searchBarOffsetY - dy).roundToInt().coerceIn(-topPaddingPx, 0)
                    return Offset.Zero
                }
            }
        }

        if (listMode == 0) {
            val listState = rememberLazyGridState()
            // Match browse folder list: no extra horizontal marginH (ListItem inset only).
            val listPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + marginV,
                bottom = paddingValues.calculateBottomPadding() + marginV,
            )
            FastScrollLazyVerticalGrid(
                columns = GalleryGridDefaults.listColumns(),
                modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                state = listState,
                contentPadding = listPadding,
            ) {
                if (directoryItems.isNotEmpty()) {
                    items(directoryItems, key = { "dir-${it.gid}" }) { info ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {},
                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                            enableDismissFromStartToEnd = false,
                            onDismiss = { deleteEntry(info) },
                        ) {
                            HistoryListItem(
                                onClick = { openEntry(info) },
                                onLongClick = { deleteEntry(info) },
                                info = info,
                                showPages = showPages,
                                showProgress = showProgress,
                                modifier = Modifier.fillMaxWidth(),
                                overflow = overflowFor(info),
                            )
                        }
                    }
                    // Gap under dirs: tap toggles expand/collapse when there is overflow.
                    if (hasFileItems || canExpandDirectories) {
                        item(
                            key = "dir-gap",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            HistoryDirectorySectionGap(
                                tappable = canExpandDirectories,
                                onToggle = { directoriesExpanded = !directoriesExpanded },
                            )
                        }
                    }
                }
                items(historyItems, key = { it.gid }) { info ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {},
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                        enableDismissFromStartToEnd = false,
                        onDismiss = { deleteEntry(info) },
                    ) {
                        HistoryListItem(
                            onClick = { openEntry(info) },
                            onLongClick = { deleteEntry(info) },
                            info = info,
                            showPages = showPages,
                            showProgress = showProgress,
                            modifier = Modifier.fillMaxWidth(),
                            overflow = overflowFor(info),
                        )
                    }
                }
            }
        } else {
            val gridState = rememberLazyGridState()
            val gridSpacing = GalleryGridDefaults.spacedBy()
            FastScrollLazyVerticalGrid(
                columns = GalleryGridDefaults.columns(),
                modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                state = gridState,
                contentPadding = GalleryGridDefaults.contentPadding(paddingValues),
                verticalArrangement = gridSpacing,
                horizontalArrangement = gridSpacing,
            ) {
                if (directoryItems.isNotEmpty()) {
                    items(directoryItems, key = { "dir-${it.gid}" }) { info ->
                        HistoryDirectoryGridItem(
                            info = info,
                            onClick = { openEntry(info) },
                            onLongClick = { deleteEntry(info) },
                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                            overflow = overflowFor(info),
                        )
                    }
                    // Gap under dirs: tap toggles expand/collapse when there is overflow.
                    if (hasFileItems || canExpandDirectories) {
                        item(
                            key = "dir-gap",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            HistoryDirectorySectionGap(
                                tappable = canExpandDirectories,
                                onToggle = { directoriesExpanded = !directoriesExpanded },
                            )
                        }
                    }
                }
                items(historyItems, key = { it.gid }) { info ->
                    HistoryGridItem(
                        info = info,
                        onClick = { openEntry(info) },
                        onLongClick = { deleteEntry(info) },
                        showPages = showPages,
                        showProgress = showProgress,
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                        overflow = overflowFor(info),
                    )
                }
            }
        }

        if (!preparingHistory && directoryItems.isEmpty() && historyItems.isEmpty() && allFileItems.isEmpty()) {
            Column(
                modifier = Modifier.padding(paddingValues).padding(horizontal = marginH).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = EhIcons.Big.Default.History,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Max browse-dir pins shown per column in History list mode (collapsed). */
private const val HISTORY_DIRECTORY_LIST_LIMIT = 5

/** Max rows of browse-dir pins shown in History grid mode (collapsed). */
private const val HISTORY_DIRECTORY_GRID_ROWS = 2

/** Extra space under the dir pin strip before main history items (also expand hit target). */
private val HISTORY_DIRECTORY_SECTION_GAP = 16.dp

/**
 * Spacer between directory pins and main history.
 * When [tappable], tap toggles expand/collapse of the dir section (**no ripple**).
 */
@Composable
private fun HistoryDirectorySectionGap(
    tappable: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HISTORY_DIRECTORY_SECTION_GAP)
            .then(
                if (tappable) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                },
            ),
    )
}
