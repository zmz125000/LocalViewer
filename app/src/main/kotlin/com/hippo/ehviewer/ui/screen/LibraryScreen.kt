package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalStaggeredGrid
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.LocalGalleryGridItem
import com.hippo.ehviewer.ui.main.LocalGalleryListItem
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt

@Destination<RootGraph>(start = true)
@Composable
fun AnimatedVisibilityScope.LibraryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val title = stringResource(id = R.string.library)
    val hint = stringResource(R.string.search_bar_hint, title)

    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    DrawerHandle(!searchBarExpanded)

    val density = LocalDensity.current
    val scanning by LocalLibrary.scanning.collectAsState()
    val galleries by remember(keyword) {
        if (keyword.isBlank()) {
            LocalLibrary.galleriesFlow()
        } else {
            LocalLibrary.searchGalleriesFlow(keyword.trim())
        }
    }.collectAsState(initial = emptyList())

    val listMode by Settings.listMode.collectAsState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val thumbColumns by Settings.thumbColumns.collectAsState()
    val cardHeight by collectListThumbSizeAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val listInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)
    val gridInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_grid_interval)

    fun openGallery(gallery: LocalGalleryEntity) {
        // Navigation must run on the main thread — Compose crashes if navigate() is
        // called from Dispatchers.IO ("Cannot start a writer when a reader is pending").
        val info = gallery.toBaseGalleryInfo()
        launchIO { LocalHistory.recordLibraryGallery(gallery) }
        if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
            navToReader(gallery.contentPath)
        } else {
            navToLocalFolderReader(gallery.contentPath, info)
        }
    }

    fun refresh() {
        launch {
            refreshing = true
            runCatching { LocalLibrary.rescanAll() }
            refreshing = false
        }
    }

    SearchBarScreen(
        onApplySearch = { keyword = it },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
        title = title,
        searchFieldHint = hint,
        searchBarOffsetY = { searchBarOffsetY },
        trailingIcon = {
            IconButton(
                onClick = { refresh() },
                enabled = !scanning && !refreshing,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
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

        PullToRefreshBox(
            isRefreshing = refreshing || scanning,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (galleries.isEmpty() && !scanning && !refreshing) {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(horizontal = marginH)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.library_empty),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (listMode == 0) {
                val listState = rememberLazyListState()
                FastScrollLazyColumn(
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    state = listState,
                    contentPadding = paddingValues,
                    verticalArrangement = Arrangement.spacedBy(listInterval),
                ) {
                    items(galleries, key = { it.id }) { gallery ->
                        LocalGalleryListItem(
                            gallery = gallery,
                            onClick = { openGallery(gallery) },
                            showPages = showPages,
                            showProgress = showProgress,
                            modifier = Modifier
                                .height(cardHeight)
                                .padding(horizontal = marginH)
                                .fillMaxWidth(),
                        )
                    }
                }
            } else {
                val gridState = rememberLazyStaggeredGridState()
                FastScrollLazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(thumbColumns),
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    state = gridState,
                    contentPadding = paddingValues,
                    verticalItemSpacing = gridInterval,
                    horizontalArrangement = Arrangement.spacedBy(gridInterval),
                ) {
                    items(galleries, key = { it.id }) { gallery ->
                        LocalGalleryGridItem(
                            gallery = gallery,
                            onClick = { openGallery(gallery) },
                            showPages = showPages,
                            showProgress = showProgress,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            if (scanning && galleries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator()
                }
            }
        }
    }
}
