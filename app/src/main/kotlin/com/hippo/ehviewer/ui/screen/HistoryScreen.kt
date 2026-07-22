package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.big.History
import com.ehviewer.core.ui.util.Await
import com.ehviewer.core.ui.util.rememberInVM
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.buildLocalBrowseStack
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.HistoryGridItem
import com.hippo.ehviewer.ui.main.HistoryListItem
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.HistoryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val title = stringResource(id = R.string.history)
    val hint = stringResource(R.string.search_bar_hint, title)
    val animateItems by Settings.animateItems.collectAsState()

    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    var keyword by rememberSaveable { mutableStateOf("") }

    DrawerHandle(!searchBarExpanded)

    val density = LocalDensity.current
    val historyData = rememberInVM(keyword) {
        Pager(config = PagingConfig(pageSize = 20, jumpThreshold = 40)) {
            if (keyword.isNotEmpty()) {
                EhDB.searchHistory(keyword)
            } else {
                EhDB.historyLazyList
            }
        }.flow.cachedIn(viewModelScope)
    }.collectAsLazyPagingItems()

    val listMode by Settings.listMode.collectAsState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val cardHeight by collectListThumbSizeAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)
    val listInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)

    fun openEntry(info: GalleryEntity) {
        launch {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = withIOContext { LocalLibrary.loadGallery(target.galleryId) }
                    if (local == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        historyData.refresh()
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
                        historyData.refresh()
                        return@launch
                    }
                    BrowseSession.localStack = buildLocalBrowseStack(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = target.relativePath,
                    )
                    navigate(FolderBrowserScreenDestination(fromHistory = true))
                }
                is LocalHistoryTarget.SmbBrowseFolder -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        historyData.refresh()
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
                        historyData.refresh()
                    }
                }
            }
        }
    }

    fun deleteEntry(info: GalleryEntity) {
        launch {
            EhDB.deleteHistoryInfo(info)
            historyData.refresh()
        }
    }

    SearchBarScreen(
        onApplySearch = {
            keyword = it
            historyData.refresh()
        },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
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
                        historyData.refresh()
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
                items(
                    count = historyData.itemCount,
                    key = historyData.itemKey(key = { item -> item.gid }),
                    contentType = historyData.itemContentType(),
                ) { index ->
                    val info = historyData[index]
                    if (info != null) {
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
                    } else {
                        Spacer(modifier = Modifier.height(cardHeight).fillMaxWidth())
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
                items(
                    count = historyData.itemCount,
                    key = historyData.itemKey(key = { item -> item.gid }),
                    contentType = historyData.itemContentType(),
                ) { index ->
                    val info = historyData[index]
                    if (info != null) {
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
        }

        Await(keyword, { delay(200) }) {
            if (historyData.itemCount == 0) {
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
                    val saveHistory by Settings.saveHistory.collectAsState()
                    val emptyHint = when {
                        !saveHistory && keyword.isEmpty() -> stringResource(id = R.string.history_disabled)
                        keyword.isEmpty() -> stringResource(id = R.string.no_history)
                        else -> stringResource(id = R.string.gallery_list_empty_hit)
                    }
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}
