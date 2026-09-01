package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.ElevatedCard
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.util.rememberInVM
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.FavoriteBrowseSource
import com.hippo.ehviewer.library.FileArchiveByteSource
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.hideDuplicateGalleriesPreferMediaStore
import com.hippo.ehviewer.library.resolveFavoriteBrowseSources
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseListSupportingContent
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.CoverImage
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.LocalGalleryGridItem
import com.hippo.ehviewer.ui.main.LocalGalleryListItem
import com.hippo.ehviewer.ui.main.browseFileExtensionLabel
import com.hippo.ehviewer.ui.main.browseListSupportingLine
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToLocalZipFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.hippo.ehviewer.ui.openLocalFolderPhotoGrid
import com.hippo.ehviewer.ui.openSmbBrowseDir
import com.hippo.ehviewer.ui.openWebDavBrowseDir
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

@Destination<RootGraph>(start = true)
@Composable
fun AnimatedVisibilityScope.LibraryScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val title = stringResource(id = R.string.library)
    val hint = stringResource(R.string.search_bar_hint, title)
    val addedToFavourites = stringResource(id = R.string.add_to_favourites)
    val removedFromFavourites = stringResource(id = R.string.remove_from_favourites)

    var keyword by rememberSaveable { mutableStateOf("") }
    var searchFocused by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    // Survive NavHost dispose/restore (e.g. open favourite folder → back).
    // collectAsState(initial=empty) remounted an empty list for one frame and
    // coerced LazyList scroll to top; VM-held state keeps last data + scroll position.
    val listState = rememberInVM { LazyListState() }
    val gridState = rememberInVM { LazyGridState() }
    var searchBarOffsetY by rememberInVM { mutableIntStateOf(0) }
    // Always keep the full library stream; filter client-side as the user types.
    val rawGalleries by rememberInVM {
        mutableStateOf(emptyList<LocalGalleryEntity>()).also { state ->
            viewModelScope.launch {
                LocalLibrary.galleriesFlow().collect { state.value = it }
            }
        }
    }
    val allGalleries = rawGalleries
    val roots by rememberInVM {
        mutableStateOf(emptyList<LibraryRootEntity>()).also { state ->
            viewModelScope.launch {
                LocalLibrary.rootsFlow().collect { state.value = it }
            }
        }
    }
    val smbSources by rememberInVM {
        mutableStateOf(emptyList<SmbSourceEntity>()).also { state ->
            viewModelScope.launch {
                SmbRepository.sourcesFlow().collect { state.value = it }
            }
        }
    }
    val webDavSources by rememberInVM {
        mutableStateOf(emptyList<WebDavSourceEntity>()).also { state ->
            viewModelScope.launch {
                WebDavRepository.sourcesFlow().collect { state.value = it }
            }
        }
    }

    DrawerHandle(!searchFocused)

    val density = LocalDensity.current
    val scanning by LocalLibrary.scanning.collectAsState()
    // Hide cross-source duplicates in UI (prefer MediaStore); DB rows stay intact.
    val allVisibleGalleries = remember(rawGalleries) {
        rawGalleries.hideDuplicateGalleriesPreferMediaStore()
    }
    val libraryRecentOpen by Settings.libraryRecentOpen.collectAsState()
    val librarySortModePref by Settings.librarySortMode.collectAsState()
    val librarySortMode = LibrarySortMode.fromPref(librarySortModePref)
    // HISTORY.TIME by gallery gid — Last open pin floats recently opened above Name/Date.
    val historyTimeByGid by rememberInVM {
        mutableStateOf(emptyMap<Long, Long>()).also { state ->
            viewModelScope.launch {
                EhDB.historyTimeListFlow.collect { rows ->
                    state.value = rows.associate { it.gid to it.time }
                }
            }
        }
    }
    // Live in-list filter.
    // Name + Last open: HISTORY pin then title (old recent-open toggle).
    // Date + Last open: blend max(last-open time, scan mtime), then title.
    val galleries = remember(
        allVisibleGalleries,
        keyword,
        historyTimeByGid,
        libraryRecentOpen,
        librarySortMode,
    ) {
        val q = keyword.trim()
        val filtered = if (q.isEmpty()) {
            allVisibleGalleries
        } else {
            allVisibleGalleries.filter { it.title.contains(q, ignoreCase = true) }
        }
        when {
            librarySortMode == LibrarySortMode.Date && libraryRecentOpen ->
                filtered.sortedWith(
                    compareByDescending<LocalGalleryEntity> {
                        maxOf(historyTimeByGid[it.id] ?: 0L, it.mtime)
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                )
            librarySortMode == LibrarySortMode.Date ->
                filtered.sortedWith(
                    compareByDescending<LocalGalleryEntity> { it.mtime }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                )
            libraryRecentOpen ->
                filtered.sortedWith(
                    compareByDescending<LocalGalleryEntity> { historyTimeByGid[it.id] ?: 0L }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                )
            else ->
                filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }

    val favoriteKeys by Settings.favoriteBrowseSources.collectAsState()
    val favorites = remember(roots, smbSources, webDavSources, allGalleries, favoriteKeys) {
        resolveFavoriteBrowseSources(roots, smbSources, webDavSources, allGalleries, favoriteKeys)
    }
    // Hide favourites section while filtering.
    val showFavorites = keyword.isBlank() && favorites.isNotEmpty()

    val listMode by Settings.listMode.collectAsState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)

    fun notifyFavoriteToggle(nowFavorite: Boolean) {
        // launch {
        //     snackbar(if (nowFavorite) addedToFavourites else removedFromFavourites)
        // }
    }

    fun openGallery(gallery: LocalGalleryEntity) {
        // Navigation must run on the main thread — Compose crashes if navigate() is
        // called from Dispatchers.IO ("Cannot start a writer when a reader is pending").
        // Playlist = visible library list so double-tap prev/next walks that order,
        // not filesystem parent siblings (often only one folder under a path).
        if (keyword.isNotBlank()) launchIO { recordDeviceSearchHistory(keyword) }
        ReaderGalleryPlaylist.setFromLibrary(galleries)
        val info = gallery.toBaseGalleryInfo()
        launchIO { LocalHistory.recordLibraryGallery(gallery) }
        if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
            // Pass info so read progress uses library id (same as progress chip).
            navToReader(gallery.contentPath, info)
        } else {
            val zip = ZipPaths.parse(gallery.contentPath)
            if (zip != null) {
                val (zipAbs, member) = zip
                val inner = if (member == "." || member.isEmpty()) "" else member
                launchIO {
                    val names = runCatching {
                        val cd = ZipCentralDirectory.open(FileArchiveByteSource(java.io.File(zipAbs)))
                            ?: return@runCatching emptyList()
                        ZipAsDirListing.directImageNames(cd, inner)
                    }.getOrDefault(emptyList())
                    if (names.isEmpty()) {
                        snackbar(string(R.string.browse_open_failed))
                        return@launchIO
                    }
                    withUIContext {
                        navToLocalZipFolderReader(
                            zipPath = zipAbs,
                            innerRel = inner,
                            imageNames = names,
                            info = info,
                        )
                    }
                }
            } else if (Settings.photoGridMode.value) {
                // Tap → photo-grid virtual folder (same as browse primary when mode on).
                val root = roots.firstOrNull { it.id == gallery.rootId }
                val rootPath = root?.let { LocalLibrary.rootPath(it) }
                if (root == null || rootPath == null) {
                    navToLocalFolderReader(gallery.contentPath, info)
                } else {
                    openLocalFolderPhotoGrid(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = gallery.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                        title = gallery.title,
                        fromLibrary = true,
                    )
                }
            } else {
                navToLocalFolderReader(gallery.contentPath, info)
            }
        }
    }

    fun toggleGalleryFavorite(gallery: LocalGalleryEntity) {
        notifyFavoriteToggle(BrowseFavorites.toggleGallery(gallery.id))
    }

    /** Favourites strip: long-press always unfavourites (toggle on a pin removes it). */
    fun toggleFavorite(fav: FavoriteBrowseSource) {
        notifyFavoriteToggle(
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
            },
        )
    }

    fun openFavorite(fav: FavoriteBrowseSource) {
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
            is FavoriteBrowseSource.Smb -> {
                openSmbBrowseDir(
                    sourceId = fav.source.id,
                    remoteDir = "",
                    fromLibrary = true,
                )
            }
            is FavoriteBrowseSource.WebDav -> {
                openWebDavBrowseDir(
                    sourceId = fav.source.id,
                    remoteDir = "",
                    fromLibrary = true,
                )
            }
            is FavoriteBrowseSource.Gallery -> openGallery(fav.gallery)
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
            is FavoriteBrowseSource.SmbFolder -> {
                openSmbBrowseDir(
                    sourceId = fav.source.id,
                    remoteDir = fav.relativePath,
                    fromLibrary = true,
                )
            }
            is FavoriteBrowseSource.WebDavFolder -> {
                openWebDavBrowseDir(
                    sourceId = fav.source.id,
                    remoteDir = fav.relativePath,
                    fromLibrary = true,
                )
            }
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
        onFilterChange = { keyword = it },
        onFocusChange = { searchFocused = it },
        title = title,
        searchFieldHint = hint,
        searchBarOffsetY = { searchBarOffsetY },
        leadingIcon = {
            // Standalone library menu: sort / layout / display + startup scan.
            LibraryViewModeMenu()
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

        val isEmpty = galleries.isEmpty() && !showFavorites
        Box(Modifier.fillMaxSize()) {
            // Always keep the Lazy list/grid mounted so scroll state is not recreated when
            // empty ↔ non-empty briefly flips (e.g. re-subscribe after pop back).
            if (listMode == 0) {
                // Match browse folder list: no extra horizontal margin (ListItem has its own
                // inset). Only top/bottom from scaffold so the search bar does not cover rows.
                val listPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + marginV,
                    bottom = paddingValues.calculateBottomPadding() + marginV,
                )
                FastScrollLazyColumn(
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    state = listState,
                    contentPadding = listPadding,
                ) {
                    if (showFavorites) {
                        item(key = "fav-hdr") {
                            // Extra list margin so section titles are not flush to the screen edge
                            // (rows stay edge-aligned with folder ListItems).
                            BrowseSectionHeader(
                                stringResource(R.string.favourite),
                                modifier = Modifier.padding(horizontal = marginH),
                            )
                        }
                        items(favorites, key = { "fav-${it.key}" }) { fav ->
                            when (fav) {
                                is FavoriteBrowseSource.Gallery -> LocalGalleryListItem(
                                    gallery = fav.gallery,
                                    onClick = { openGallery(fav.gallery) },
                                    onLongClick = { toggleFavorite(fav) },
                                    showPages = showPages,
                                    showProgress = showProgress,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                else -> FavoriteSourceListRow(
                                    fav = fav,
                                    onClick = { openFavorite(fav) },
                                    onLongClick = { toggleFavorite(fav) },
                                )
                            }
                        }
                        if (galleries.isNotEmpty()) {
                            item(key = "gal-hdr") {
                                BrowseSectionHeader(
                                    stringResource(R.string.library),
                                    modifier = Modifier.padding(horizontal = marginH),
                                )
                            }
                        }
                    }
                    items(galleries, key = { it.id }) { gallery ->
                        LocalGalleryListItem(
                            gallery = gallery,
                            onClick = { openGallery(gallery) },
                            onLongClick = { toggleGalleryFavorite(gallery) },
                            showPages = showPages,
                            showProgress = showProgress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                val gridSpacing = GalleryGridDefaults.spacedBy()
                FastScrollLazyVerticalGrid(
                    columns = GalleryGridDefaults.columns(),
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    state = gridState,
                    contentPadding = GalleryGridDefaults.contentPadding(paddingValues),
                    verticalArrangement = gridSpacing,
                    horizontalArrangement = gridSpacing,
                ) {
                    if (showFavorites) {
                        item(
                            key = "fav-hdr",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            BrowseSectionHeader(stringResource(R.string.favourite))
                        }
                        items(favorites, key = { "fav-${it.key}" }) { fav ->
                            FavoriteSourceGridCell(
                                fav = fav,
                                onClick = { openFavorite(fav) },
                                onLongClick = { toggleFavorite(fav) },
                            )
                        }
                        if (galleries.isNotEmpty()) {
                            item(
                                key = "gal-hdr",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                BrowseSectionHeader(stringResource(R.string.library))
                            }
                        }
                    }
                    items(galleries, key = { it.id }) { gallery ->
                        LocalGalleryGridItem(
                            gallery = gallery,
                            onClick = { openGallery(gallery) },
                            onLongClick = { toggleGalleryFavorite(gallery) },
                            showPages = showPages,
                            showProgress = showProgress,
                        )
                    }
                }
            }

            if (isEmpty && !scanning && !refreshing) {
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
            }

            if (scanning && isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun FavoriteSourceListRow(
    fav: FavoriteBrowseSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val folderThumbKey = folderFavoriteThumbKey(fav)
    var resolvedThumb by remember(folderThumbKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(folderThumbKey) {
        resolvedThumb = withIOContext { HistoryThumbKey.resolveReadablePath(folderThumbKey) }
    }
    val leadSize = 56.dp
    val listDecodePx = CoverThumb.listDecodePx()
    ListItem(
        headlineContent = { Text(fav.displayName) },
        supportingContent = {
            // Same type icon idea as favourite grid caption (Lan/Cloud badge / source glyph).
            BrowseListSupportingContent(
                text = favoriteMetaLine(fav),
                typeIcon = favoriteListTypeIcon(fav),
            )
        },
        leadingContent = {
            when {
                fav is FavoriteBrowseSource.Gallery -> CoverImage(
                    coverPath = fav.gallery.coverPath,
                    sizePx = listDecodePx,
                    archiveContentPath = fav.gallery.contentPath.takeIf {
                        fav.gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE
                    },
                    placeholder = if (fav.gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        Icons.Default.Inventory2
                    } else {
                        Icons.Default.Folder
                    },
                    modifier = Modifier
                        .size(leadSize)
                        .clip(ShapeDefaults.Medium),
                )
                resolvedThumb != null -> CoverImage(
                    coverPath = folderThumbKey,
                    sizePx = listDecodePx,
                    placeholder = Icons.Default.Folder,
                    modifier = Modifier
                        .size(leadSize)
                        .clip(ShapeDefaults.Medium),
                )
                else -> Box(
                    modifier = Modifier.size(leadSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        favoriteIcon(fav),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    )
}

/**
 * Square favourite grid cell (column width from [GalleryGridDefaults]).
 * Sources: 48.dp icon above caption.
 * Galleries + folder favourites with a **cache-hit** thumb: full-bleed cover + bottom
 * label scrim (same as favourite gallery). Miss / no key keeps classic icon layout.
 */
@Composable
private fun FavoriteSourceGridCell(
    fav: FavoriteBrowseSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
) {
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    val folderThumbKey = folderFavoriteThumbKey(fav)
    var resolvedFolderThumb by remember(folderThumbKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(folderThumbKey) {
        resolvedFolderThumb = withIOContext { HistoryThumbKey.resolveReadablePath(folderThumbKey) }
    }
    val useGalleryThumbStyle = fav is FavoriteBrowseSource.Gallery || resolvedFolderThumb != null
    ElevatedCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        if (useGalleryThumbStyle) {
            // Full-bleed thumb; name on a highly transparent bottom bar.
            Box(Modifier.fillMaxSize().clip(ShapeDefaults.Medium)) {
                val gridDecodePx = CoverThumb.gridDecodePx(
                    screenWidthDp = LocalConfiguration.current.screenWidthDp,
                    columns = GalleryGridDefaults.columnCount(),
                    margin = GalleryGridDefaults.margin(),
                    gutter = GalleryGridDefaults.gutter(),
                )
                when (fav) {
                    is FavoriteBrowseSource.Gallery -> {
                        val gallery = fav.gallery
                        CoverImage(
                            coverPath = gallery.coverPath,
                            sizePx = gridDecodePx,
                            archiveContentPath = gallery.contentPath.takeIf {
                                gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE
                            },
                            placeholder = if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                                Icons.Default.Inventory2
                            } else {
                                Icons.Default.Folder
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> CoverImage(
                        coverPath = folderThumbKey,
                        sizePx = gridDecodePx,
                        placeholder = Icons.Default.Folder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = fav.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    // Same default onSurface as other fav / dir cells; scrim follows theme.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(horizontal = namePadH)
                        .padding(top = 4.dp, bottom = namePadBottom),
                )
            }
        } else {
            // Network folders: main Folder icon + small Lan/Cloud badge left of caption
            // (same size as labelMedium).
            val networkBadge = when (fav) {
                is FavoriteBrowseSource.SmbFolder -> Icons.Default.Lan
                is FavoriteBrowseSource.WebDavFolder -> Icons.Default.Cloud
                else -> null
            }
            val labelIconSize = with(LocalDensity.current) {
                MaterialTheme.typography.labelMedium.fontSize.toDp()
            }
            // ElevatedCard content is already a fillMaxSize Column.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(ShapeDefaults.Medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    favoriteIcon(fav),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = namePadH)
                    .padding(bottom = namePadBottom),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (networkBadge != null) {
                    Icon(
                        networkBadge,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(labelIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = fav.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Stored cover key for folder favourites only (null for whole sources / galleries). */
private fun folderFavoriteThumbKey(fav: FavoriteBrowseSource): String? = when (fav) {
    is FavoriteBrowseSource.LocalFolder -> fav.thumbKey
    is FavoriteBrowseSource.SmbFolder -> fav.thumbKey
    is FavoriteBrowseSource.WebDavFolder -> fav.thumbKey
    else -> null
}

/** Same meta tokens as library / folder list (`Dir` / `SMB` / `WebDAV` / `Folder` / `ZIP` · …). */
@Composable
private fun favoriteMetaLine(fav: FavoriteBrowseSource): String {
    val typeLabel = when (fav) {
        is FavoriteBrowseSource.Local -> "Dir"
        is FavoriteBrowseSource.Smb -> "SMB"
        is FavoriteBrowseSource.WebDav -> "WebDAV"
        is FavoriteBrowseSource.LocalFolder,
        is FavoriteBrowseSource.SmbFolder,
        is FavoriteBrowseSource.WebDavFolder,
        -> "Dir"
        is FavoriteBrowseSource.Gallery -> {
            val g = fav.gallery
            if (g.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                browseFileExtensionLabel(g.contentPath)
            } else {
                "Folder"
            }
        }
    }
    return when (fav) {
        is FavoriteBrowseSource.Gallery -> {
            val g = fav.gallery
            val archiveSize = remember(g.contentPath, g.kind) {
                if (g.kind != LOCAL_GALLERY_KIND_ARCHIVE) {
                    0L
                } else {
                    runCatching {
                        java.io.File(g.contentPath).takeIf { it.isFile }?.length() ?: 0L
                    }.getOrDefault(0L)
                }
            }
            browseListSupportingLine(
                typeLabel = typeLabel,
                sizeBytes = archiveSize,
                pageCount = when {
                    g.kind == LOCAL_GALLERY_KIND_ARCHIVE && archiveSize > 0L -> 0
                    else -> g.pageCount
                },
                lastModifiedMs = g.mtime,
            )
        }
        else -> browseListSupportingLine(typeLabel = typeLabel)
    }
}

/** Leading / center glyph for favourite grid icon layout (Folder for network folder pins). */
private fun favoriteIcon(fav: FavoriteBrowseSource): ImageVector = when (fav) {
    is FavoriteBrowseSource.Local ->
        if (fav.root.isLibraryRole) Icons.AutoMirrored.Filled.LibraryBooks else Icons.Default.Folder
    is FavoriteBrowseSource.Smb -> Icons.Default.Lan
    is FavoriteBrowseSource.WebDav -> Icons.Default.Cloud
    is FavoriteBrowseSource.Gallery -> Icons.Default.Folder
    is FavoriteBrowseSource.LocalFolder -> Icons.Default.Folder
    // Network folders: Folder main glyph; grid cell adds Lan/Cloud badge.
    is FavoriteBrowseSource.SmbFolder -> Icons.Default.Folder
    is FavoriteBrowseSource.WebDavFolder -> Icons.Default.Folder
}

/**
 * Distinctive type icon for favourite list meta row (matches grid caption badge /
 * source glyph: Lan/Cloud for network, Inventory2 for archives, Folder otherwise).
 */
private fun favoriteListTypeIcon(fav: FavoriteBrowseSource): ImageVector = when (fav) {
    is FavoriteBrowseSource.Local ->
        if (fav.root.isLibraryRole) Icons.AutoMirrored.Filled.LibraryBooks else Icons.Default.Folder
    is FavoriteBrowseSource.Smb, is FavoriteBrowseSource.SmbFolder -> Icons.Default.Lan
    is FavoriteBrowseSource.WebDav, is FavoriteBrowseSource.WebDavFolder -> Icons.Default.Cloud
    is FavoriteBrowseSource.LocalFolder -> Icons.Default.Folder
    is FavoriteBrowseSource.Gallery ->
        if (fav.gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
            Icons.Default.Inventory2
        } else {
            Icons.Default.Folder
        }
}
