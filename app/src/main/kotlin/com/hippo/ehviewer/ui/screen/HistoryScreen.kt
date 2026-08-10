package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyColumn
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
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.buildLocalBrowseStack
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.WebDavBrowserScreenDestination
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.HistoryGridItem
import com.hippo.ehviewer.ui.main.HistoryListItem
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.navToSmbFolderReader
import com.hippo.ehviewer.ui.navToSmbStreamArchiveReader
import com.hippo.ehviewer.ui.navToWebDavFolderReader
import com.hippo.ehviewer.ui.navToWebDavStreamArchiveReader
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.HistoryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val title = stringResource(id = R.string.history)
    val hint = stringResource(R.string.search_bar_hint, title)
    val animateItems by Settings.animateItems.collectAsState()

    var searchFocused by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    // Live filter text from the search field (updates as the user types).
    var keyword by rememberSaveable { mutableStateOf("") }

    DrawerHandle(!searchFocused)

    val density = LocalDensity.current
    // Full history stream; filter client-side so typing does not rebuild a PagingSource.
    val allHistory by rememberInVM {
        mutableStateOf(emptyList<GalleryEntity>()).also { state ->
            viewModelScope.launch {
                EhDB.historyListFlow.collect { state.value = it }
            }
        }
    }
    val filterQuery = keyword.trim()
    val historyItems = remember(allHistory, filterQuery) {
        if (filterQuery.isEmpty()) {
            allHistory
        } else {
            allHistory.filter { info ->
                info.title?.contains(filterQuery, ignoreCase = true) == true ||
                    info.titleJpn?.contains(filterQuery, ignoreCase = true) == true
            }
        }
    }

    val listMode by Settings.listMode.collectAsState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val cardHeight by collectListThumbSizeAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)
    val listInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)

    fun openEntry(info: GalleryEntity) {
        if (filterQuery.isNotEmpty()) {
            launch { withIOContext { recordDeviceSearchHistory(filterQuery) } }
        }
        launch {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = withIOContext { LocalLibrary.loadGallery(target.galleryId) }
                    if (local == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        navToReader(local.contentPath)
                    } else {
                        navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                    }
                }
                is LocalHistoryTarget.LocalBrowseFolder -> {
                    val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    BrowseSession.localStack = buildLocalBrowseStack(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = target.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                    )
                    navigate(FolderBrowserScreenDestination(fromHistory = true))
                }
                is LocalHistoryTarget.SmbBrowseFolder -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val segments = target.relativePath.split('/').filter { it.isNotEmpty() }
                    BrowseSession.setSmbSegments(source.id, segments)
                    navigate(
                        SmbBrowserScreenDestination(
                            sourceId = source.id,
                            initialRelativePath = target.relativePath,
                            fromHistory = true,
                        ),
                    )
                }
                is LocalHistoryTarget.WebDavBrowseFolder -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val segments = target.relativePath.split('/').filter { it.isNotEmpty() }
                    BrowseSession.setWebDavSegments(source.id, segments)
                    navigate(
                        WebDavBrowserScreenDestination(
                            sourceId = source.id,
                            initialRelativePath = target.relativePath,
                            fromHistory = true,
                        ),
                    )
                }
                is LocalHistoryTarget.LocalFolderGallery -> {
                    // Open reader; back → parent directory (same pattern as archives).
                    val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val fullStack = buildLocalBrowseStack(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = target.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                    )
                    val galleryPath = fullStack.last().path
                    val parentRel = parentRelativeOfFile(target.relativePath)
                    BrowseSession.localStack = buildLocalBrowseStack(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = parentRel,
                        preferMediaStore = root.prefersMediaStore,
                    )
                    navigate(FolderBrowserScreenDestination(fromHistory = true))
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = LOCAL_GALLERY_TOKEN,
                        title = info.title ?: target.relativePath.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                    )
                    navToLocalFolderReader(galleryPath, gi)
                }
                is LocalHistoryTarget.SmbFolderGallery -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remoteDir.trim('/')
                    val parentRel = parentRelativeOfFile(remote)
                    val segments = parentRel.split('/').filter { it.isNotEmpty() }
                    BrowseSession.setSmbSegments(source.id, segments)
                    navigate(
                        SmbBrowserScreenDestination(
                            sourceId = source.id,
                            initialRelativePath = parentRel,
                            fromHistory = true,
                        ),
                    )
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = LOCAL_GALLERY_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                    )
                    // Empty names → reader re-lists full image set.
                    navToSmbFolderReader(source.id, remote, emptyList(), gi)
                }
                is LocalHistoryTarget.WebDavFolderGallery -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remoteDir.trim('/')
                    val parentRel = parentRelativeOfFile(remote)
                    val segments = parentRel.split('/').filter { it.isNotEmpty() }
                    BrowseSession.setWebDavSegments(source.id, segments)
                    navigate(
                        WebDavBrowserScreenDestination(
                            sourceId = source.id,
                            initialRelativePath = parentRel,
                            fromHistory = true,
                        ),
                    )
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = LOCAL_GALLERY_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                    )
                    navToWebDavFolderReader(source.id, remote, emptyList(), gi)
                }
                is LocalHistoryTarget.LocalArchive -> {
                    // Align with folder gallery: back from reader → parent browse path
                    // (fromHistory FAB still jumps straight to History).
                    val parent = withIOContext {
                        LocalLibrary.resolveArchiveBrowseParent(target.path)
                    }
                    if (parent != null) {
                        val root = withIOContext { LocalLibrary.loadRoot(parent.rootId) }
                        BrowseSession.localStack = buildLocalBrowseStack(
                            rootId = parent.rootId,
                            rootDisplayName = parent.rootDisplayName,
                            rootPath = parent.rootPath,
                            relativePath = parent.parentRelativePath,
                            preferMediaStore = root?.prefersMediaStore ?: false,
                        )
                        navigate(FolderBrowserScreenDestination(fromHistory = true))
                    }
                    navToReader(target.path)
                }
                is LocalHistoryTarget.SmbStreamArchive -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remotePath.trim('/')
                    val parentRel = parentRelativeOfFile(remote)
                    val segments = parentRel.split('/').filter { it.isNotEmpty() }
                    BrowseSession.setSmbSegments(source.id, segments)
                    navigate(
                        SmbBrowserScreenDestination(
                            sourceId = source.id,
                            initialRelativePath = parentRel,
                            fromHistory = true,
                        ),
                    )
                    val gi = BaseGalleryInfo(
                        gid = stableGalleryId(source.id, "smba:$remote"),
                        token = LOCAL_GALLERY_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                    )
                    navToSmbStreamArchiveReader(source.id, remote, gi)
                }
                is LocalHistoryTarget.WebDavStreamArchive -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remotePath.trim('/')
                    val parentRel = parentRelativeOfFile(remote)
                    val segments = parentRel.split('/').filter { it.isNotEmpty() }
                    BrowseSession.setWebDavSegments(source.id, segments)
                    navigate(
                        WebDavBrowserScreenDestination(
                            sourceId = source.id,
                            initialRelativePath = parentRel,
                            fromHistory = true,
                        ),
                    )
                    val gi = BaseGalleryInfo(
                        gid = stableGalleryId(source.id, "dava:$remote"),
                        token = LOCAL_GALLERY_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                    )
                    navToWebDavStreamArchiveReader(source.id, remote, gi)
                }
                is LocalHistoryTarget.Orphan -> {
                    // Legacy "local" browse rows without path metadata, or foreign EH history.
                    val local = withIOContext { LocalLibrary.loadGallery(target.gid) }
                    if (local != null) {
                        if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                            navToReader(local.contentPath)
                        } else {
                            navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                        }
                    } else {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                    }
                }
            }
        }
    }

    fun deleteEntry(info: GalleryEntity) {
        launch {
            EhDB.deleteHistoryInfo(info)
        }
    }

    SearchBarScreen(
        onFilterChange = { keyword = it },
        onFocusChange = { searchFocused = it },
        title = title,
        searchFieldHint = hint,
        searchBarOffsetY = { searchBarOffsetY },
        leadingIcon = {
            // Same pref as Library / Settings → General → List mode (0 = detail, 1 = thumb).
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
            val listState = rememberLazyListState()
            val listPadding = paddingValues + PaddingValues(marginH, marginV)
            FastScrollLazyColumn(
                modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                state = listState,
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(listInterval),
            ) {
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
                            modifier = Modifier.height(cardHeight).fillMaxWidth(),
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
                items(historyItems, key = { it.gid }) { info ->
                    HistoryGridItem(
                        info = info,
                        onClick = { openEntry(info) },
                        onLongClick = { deleteEntry(info) },
                        showPages = showPages,
                        showProgress = showProgress,
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                    )
                }
            }
        }

        if (historyItems.isEmpty()) {
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
