package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
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
    val cardHeight by collectListThumbSizeAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)
    val listInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)

    fun openGallery(gallery: LocalGalleryEntity) {
        // Navigation must run on the main thread — Compose crashes if navigate() is
        // called from Dispatchers.IO ("Cannot start a writer when a reader is pending").
        // Playlist = visible library list so double-tap prev/next walks that order,
        // not filesystem parent siblings (often only one folder under a path).
        ReaderGalleryPlaylist.setFromLibrary(galleries)
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
        leadingIcon = {
            // Same pref as Settings → General → List mode (0 = detail, 1 = thumb).
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

        Box(Modifier.fillMaxSize()) {
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
                // Match GalleryList: search-bar inset + list margins so top gap under the
                // search field equals the horizontal card inset (marginH + search padding).
                val listPadding = paddingValues + PaddingValues(marginH, marginV)
                FastScrollLazyColumn(
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    state = listState,
                    contentPadding = listPadding,
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
                                .fillMaxWidth(),
                        )
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
                    items(galleries, key = { it.id }) { gallery ->
                        LocalGalleryGridItem(
                            gallery = gallery,
                            onClick = { openGallery(gallery) },
                            showPages = showPages,
                            showProgress = showProgress,
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
