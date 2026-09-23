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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Explore
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.BrowseContentMode
import com.hippo.ehviewer.library.BrowseEntry
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.BrowseFolderId
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.BrowseVirtualKind
import com.hippo.ehviewer.library.DirPresence
import com.hippo.ehviewer.library.EmptyArchiveRegistry
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.FolderSearch
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalFolderListing
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.LocalListingJobs
import com.hippo.ehviewer.library.MediaStoreFs
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.VideoThumbnail
import com.hippo.ehviewer.library.VideoThumbnailSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.browseScrollLayoutKey
import com.hippo.ehviewer.library.filterByContentMode
import com.hippo.ehviewer.library.filterSmallGalleries
import com.hippo.ehviewer.library.isHtmlFileName
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.isZipPlainFolderListingLocal
import com.hippo.ehviewer.library.listBrowseChildrenRaw
import com.hippo.ehviewer.library.localBrowseVirtual
import com.hippo.ehviewer.library.materializeLocalEntries
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.resolveBrowsePath
import com.hippo.ehviewer.library.resolveRelative
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toBrowseSections
import com.hippo.ehviewer.library.withLocalZipCentralDirectory
import com.hippo.ehviewer.ui.LocalShowNavShortcutFab
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.OpenPdfExternally
import com.hippo.ehviewer.ui.PdfReaderMode
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.BrowseScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.LibraryScreenDestination
import com.hippo.ehviewer.ui.main.BrowseArchiveGalleryRow
import com.hippo.ehviewer.ui.main.BrowseArchiveGridItem
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowseDirectoryGridItem
import com.hippo.ehviewer.ui.main.BrowseDirectoryRow
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseFileGridItem
import com.hippo.ehviewer.ui.main.BrowseFileRow
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryGridItem
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryRow
import com.hippo.ehviewer.ui.main.BrowseFolderSection
import com.hippo.ehviewer.ui.main.BrowseOverflowActions
import com.hippo.ehviewer.ui.main.BrowseOverflowKind
import com.hippo.ehviewer.ui.main.BrowsePhotoGridImageItem
import com.hippo.ehviewer.ui.main.BrowseSaveAs
import com.hippo.ehviewer.ui.main.BrowseSearchSectionHeader
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.BrowseVideoGridItem
import com.hippo.ehviewer.ui.main.BrowseVideoRow
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.HttpShare
import com.hippo.ehviewer.ui.main.HttpShareItem
import com.hippo.ehviewer.ui.main.awaitHttpShareQr
import com.hippo.ehviewer.ui.main.browseZipAsDirTypeLabel
import com.hippo.ehviewer.ui.main.rememberBrowseSectionCollapse
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToLocalZipFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.util.addTextToClipboard
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import moe.tarsin.snackbar
import okio.Path
import okio.Path.Companion.toPath

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.FolderBrowserScreen(
    navigator: DestinationsNavigator,
    /** When opened from History, show a FAB to jump straight back (skip path climb). */
    fromHistory: Boolean = false,
    /** When opened from Library favourites, show a FAB to jump back to Library. */
    fromLibrary: Boolean = false,
) = Screen(navigator) {
    val context = LocalContext.current
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

    /**
     * Stack size of the listing that owned the Search section when a dir was opened
     * from that section. Next goUp jumps there. Overflow Open folder leaves this -1.
     */
    var searchReturnStackSize by remember { mutableIntStateOf(-1) }

    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    // Lazy-drop non-image archives when cover open reports 0 pages (EmptyArchiveRegistry).
    val emptyArchiveRev by EmptyArchiveRegistry.revision.collectAsState()
    val displayEntries = remember(entries, emptyArchiveRev) {
        EmptyArchiveRegistry.filterLocalEntries(entries)
    }
    val search = rememberBrowseFolderSearchState()
    val searchFolderKey = stack.lastOrNull()?.path?.let { BrowseSession.localFolderSearchKey(it) }.orEmpty()
    var searchHits by remember(searchFolderKey) {
        mutableStateOf(BrowseSession.peekFolderSearchHits<BrowseEntry>(searchFolderKey))
    }
    var searching by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Scroll down hides the top bar; scroll up brings it back (enterAlways).
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // FAB tracks the same enterAlways state (hide when bar collapses, show when it reappears).
    val showScrollFab by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction < 0.5f }
    }
    // Restore before filteredEntries / list so Search-section items exist when scroll applies.
    BindBrowseFolderSearch(
        folderKey = searchFolderKey.ifEmpty { null },
        search = search,
        onPathChange = { scrollBehavior.state.heightOffset = 0f },
    )
    val folderId = stack.lastOrNull()?.let { BrowseFolderId.local(it.rootId, it.relativePath) }
    val contentMode = rememberEffectiveBrowseContentMode(folderId)
    val showSmallGalleries by Settings.browseShowSmallGalleries.collectAsState()
    val smallGalleryMinPages by Settings.browseSmallGalleryMinPages.collectAsState()
    val showHiddenFiles by Settings.browseShowHiddenFiles.collectAsState()
    val showVirtualGalleries by Settings.browseShowVirtualGalleries.collectAsState()
    // Same virtual-layer rules as SMB RPC root / photo grid (not regular folder-view mode).
    val relativeDirForMode = stack.lastOrNull()?.relativePath.orEmpty()
    val virtual = localBrowseVirtual(
        photoGrid = stack.lastOrNull()?.photoGrid == true,
        videoFolder = stack.lastOrNull()?.videoFolder == true,
        zipPlainFolder = isZipPlainFolderListingLocal(relativeDirForMode, displayEntries),
    )
    val photoGrid = virtual == BrowseVirtualKind.PhotoGrid
    val videoFolder = virtual == BrowseVirtualKind.VideoFolder
    val photoGridMode by Settings.photoGridMode.collectAsState()
    val browseZipAsDir by Settings.browseZipAsDir.collectAsState()
    val filteredEntries = remember(
        displayEntries,
        search.keyword,
        contentMode,
        showSmallGalleries,
        smallGalleryMinPages,
        showHiddenFiles,
        showVirtualGalleries,
        virtual,
    ) {
        val liveSearch = search.keyword.trim().isNotEmpty()
        val base = when (virtual) {
            BrowseVirtualKind.PhotoGrid ->
                displayEntries
                    .filterIsInstance<BrowseEntry.RegularFile>()
                    .filter { isImageFileName(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            BrowseVirtualKind.VideoFolder ->
                displayEntries
                    .filterByContentMode(
                        BrowseContentMode.Video,
                        showHiddenFiles,
                        showVirtualGalleries,
                        allTypes = liveSearch,
                    )
                    .filterSmallGalleries(showSmallGalleries, smallGalleryMinPages)
            BrowseVirtualKind.ZipPlainFolder ->
                displayEntries
                    .filterByContentMode(
                        BrowseContentMode.Folder,
                        showHiddenFiles,
                        showVirtualGalleries,
                        allTypes = liveSearch,
                    )
                    .filterSmallGalleries(showSmallGalleries, smallGalleryMinPages)
            BrowseVirtualKind.RpcShareRoot,
            BrowseVirtualKind.None,
            ->
                displayEntries
                    .filterByContentMode(
                        contentMode,
                        showHiddenFiles,
                        showVirtualGalleries,
                        allTypes = liveSearch,
                    )
                    .filterSmallGalleries(showSmallGalleries, smallGalleryMinPages)
        }
        base.filterByBrowseSearch(search.keyword) { it.name }
    }

    /**
     * Image RegularFiles in the current listing — photo-grid virtual folder **and**
     * Folder-mode loose images (shared reader / cover keys).
     */
    val folderImages = remember(filteredEntries) {
        filteredEntries
            .filterIsInstance<BrowseEntry.RegularFile>()
            .filter { isImageFileName(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    /** Path the current [entries] belong to — avoids showing the wrong dir during reload. */
    var listedPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listMode by Settings.listMode.collectAsState()
    val useGrid = virtual.forceGrid || listMode == 1
    val showGalleryPages by Settings.showGalleryPages.collectAsState()
    val browseFolderThumbs by Settings.browseFolderThumbs.collectAsState()

    val scrollLayoutKey = browseScrollLayoutKey(listMode, contentMode, virtual)
    val favoriteKeys by Settings.favoriteBrowseSources.collectAsState()
    val addedToFavourites = stringResource(id = R.string.add_to_favourites)
    val removedFromFavourites = stringResource(id = R.string.remove_from_favourites)

    val current = stack.lastOrNull()
    val currentPath = current?.path
    val title = current?.title ?: stringResource(R.string.folder)
    val searchHint = stringResource(R.string.search_bar_hint, title)

    fun toggleDirFavorite(dir: BrowseEntry.Directory) {
        val frame = stack.lastOrNull() ?: return
        val rel = if (frame.relativePath.isEmpty()) dir.name else "${frame.relativePath}/${dir.name}"
        // Same cache-key idea as History: absolute cover path for Library fav cell.
        BrowseFavorites.toggleLocalFolder(frame.rootId, rel, thumbKey = dir.coverPath?.toString())
    }

    fun isDirFavorite(dir: BrowseEntry.Directory): Boolean {
        val frame = stack.lastOrNull() ?: return false
        val rel = if (frame.relativePath.isEmpty()) dir.name else "${frame.relativePath}/${dir.name}"
        return BrowseFavorites.localFolderKey(frame.rootId, rel) in favoriteKeys
    }

    LaunchedEffect(
        searchFolderKey,
        search.submittedKeyword,
        search.submitGeneration,
        showHiddenFiles,
    ) {
        val q = search.submittedKeyword
        val frame = stack.lastOrNull()
        if (q.isEmpty() || frame == null || searchFolderKey.isEmpty()) {
            searchHits = emptyList()
            searching = false
            if (searchFolderKey.isNotEmpty()) BrowseSession.clearFolderSearchHits(searchFolderKey)
            return@LaunchedEffect
        }
        val cached = BrowseSession.cachedFolderSearchHits<BrowseEntry>(
            searchFolderKey,
            q,
            search.submitGeneration,
            showHiddenFiles,
        )
        if (cached != null) {
            searchHits = cached
            searching = false
            return@LaunchedEffect
        }
        searching = true
        searchHits = emptyList()
        try {
            searchHits = LocalFolderListing.searchDirectory(
                listedPath = frame.path.toPath(),
                relativeDir = frame.relativePath,
                query = q,
                includeHidden = showHiddenFiles,
                preferMediaStore = frame.preferMediaStore,
                zipInnerRel = frame.zipInnerRel,
            ) { searchHits = it }
            BrowseSession.putFolderSearchHits(
                searchFolderKey,
                BrowseSession.FolderSearchHits(
                    submittedKeyword = q,
                    submitGeneration = search.submitGeneration,
                    includeHidden = showHiddenFiles,
                    hits = searchHits,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            BrowseSession.putFolderSearchHits(
                searchFolderKey,
                BrowseSession.FolderSearchHits(
                    submittedKeyword = q,
                    submitGeneration = search.submitGeneration,
                    includeHidden = showHiddenFiles,
                    hits = searchHits,
                ),
            )
        } finally {
            searching = false
        }
    }

    fun frameListKey(frame: BrowseSession.LocalFrame): String = if (frame.zipInnerRel != null) "${frame.path}|${frame.zipInnerRel}" else frame.path

    /** Skip the next [reload] when photo-grid already has the reader file list. */
    var skipNextListing by remember { mutableStateOf(false) }

    fun localPhotoGridNames(parent: BrowseSession.LocalFrame, galleryDir: String): List<String>? = FolderGalleryIndex.namesFromLocalParent(
        rootId = parent.rootId,
        parentPath = parent.path,
        parentRelative = parent.relativePath,
        galleryDir = galleryDir,
        zipInnerRel = parent.zipInnerRel,
    )

    fun applyLocalPhotoGridFiles(frame: BrowseSession.LocalFrame, names: List<String>) {
        entries = FolderGalleryIndex.photoGridLocalFiles(frame.path, frame.zipInnerRel, names)
        listedPath = frameListKey(frame)
        loading = false
        refreshing = false
        error = null
    }

    fun applyLocalVideoFolderFiles(frame: BrowseSession.LocalFrame, names: List<String>) {
        val effective = resolveBrowsePath(
            frame.path.toPath(),
            preferMediaStore = frame.preferMediaStore,
        )
        val previous = BrowseSession.getLocalFolderCachedListing(
            frame.rootId,
            frame.relativePath,
        )?.entries
        BrowseSession.putLocalFolderListing(
            frame.rootId,
            frame.relativePath,
            FolderGalleryIndex.mergeLibraryFolderVideos(previous, names),
            sessionCurrent = false,
            pathAlias = effective,
        )
        entries = FolderGalleryIndex.videoFolderLocalFiles(frame.path, names)
        listedPath = frameListKey(frame)
        loading = false
        refreshing = false
        error = null
    }

    /** Paint RAM listing immediately (return-from-reader / remount) like SMB. */
    fun applyCachedLocalListing(frame: BrowseSession.LocalFrame): Boolean {
        if (frame.photoGrid || frame.videoFolder) return false
        if (frame.isZipBrowse) {
            val virtualDir = ZipAsDirListing.virtualRelativeDir(
                frame.relativePath,
                frame.zipInnerRel.orEmpty(),
            )
            val cached = BrowseSession.getLocalCachedListing(
                BrowseSession.localZipListingKey(frame.rootId, virtualDir),
            ) ?: return false
            entries = ZipAsDirListing.materializeLocal(
                frame.path,
                frame.zipInnerRel.orEmpty(),
                ZipAsDirListing.presentCachedListing(cached.entries),
            )
            listedPath = frameListKey(frame)
            loading = false
            refreshing = !cached.sessionCurrent
            error = null
            return true
        }
        val effective = resolveBrowsePath(
            frame.path.toPath(),
            preferMediaStore = frame.preferMediaStore,
        )
        val cached = BrowseSession.getLocalFolderCachedListing(
            frame.rootId,
            frame.relativePath,
        ) ?: return false
        entries = materializeLocalEntries(
            effective,
            ZipAsDirListing.presentCachedListing(cached.entries),
        )
        listedPath = frameListKey(frame)
        loading = false
        refreshing = !cached.sessionCurrent
        error = null
        return true
    }

    fun isLocalListingSessionCurrent(frame: BrowseSession.LocalFrame): Boolean {
        if (frame.isZipBrowse) {
            val virtualDir = ZipAsDirListing.virtualRelativeDir(
                frame.relativePath,
                frame.zipInnerRel.orEmpty(),
            )
            return BrowseSession.isLocalListingSessionCurrent(
                BrowseSession.localZipListingKey(frame.rootId, virtualDir),
            )
        }
        return BrowseSession.isLocalFolderListingSessionCurrent(frame.rootId, frame.relativePath)
    }

    fun notifyLocalThumbFolder(frame: BrowseSession.LocalFrame?) {
        if (frame == null) {
            VideoThumbnail.onBrowseFolderLeft("local:")
            ArchiveCoverCache.onBrowseFolderLeft("local:")
            return
        }
        val key = "local:${frame.rootId}:${frame.relativePath}:${frame.zipInnerRel.orEmpty()}"
        VideoThumbnail.onBrowseFolderChanged(key)
        ArchiveCoverCache.onBrowseFolderChanged(key)
    }

    suspend fun reload(force: Boolean = false) {
        val frame = stack.lastOrNull()
        if (frame == null) {
            notifyLocalThumbFolder(null)
            entries = emptyList()
            listedPath = null
            error = null
            return
        }
        // Leave→enter folder must not wait on previous path’s stuck MMR workers.
        notifyLocalThumbFolder(frame)
        val targetPath = frameListKey(frame)
        // Photo-grid open: same complete index the reader uses — no directory scan.
        if (!force && frame.photoGrid) {
            val alreadyShown = listedPath == targetPath &&
                entries.any { it is BrowseEntry.RegularFile && isImageFileName(it.name) }
            if (alreadyShown) {
                loading = false
                refreshing = false
                return
            }
            val galleryDir = if (frame.isZipBrowse) {
                ZipAsDirListing.virtualRelativeDir(frame.relativePath, frame.zipInnerRel.orEmpty())
            } else {
                frame.relativePath
            }
            val root = LocalLibrary.loadRoot(frame.rootId)
            val rootPath = root?.let { LocalLibrary.rootPath(it) }
            val names = if (rootPath != null) {
                FolderGalleryIndex.loadLocal(
                    frame.rootId,
                    LocalFolderListing.rootConfigKey(rootPath, frame.preferMediaStore),
                    galleryDir,
                    rootAbs = rootPath,
                )
            } else {
                null
            } ?: MediaStoreFs.imageFileNames(frame.path.toPath())
            if (!names.isNullOrEmpty()) {
                applyLocalPhotoGridFiles(frame, names)
                return
            }
        }
        // Video-folder overlay: library index / DB file list — no browse classify scan.
        if (!force && frame.videoFolder && !frame.isZipBrowse) {
            val alreadyShown = listedPath == targetPath &&
                entries.any { it is BrowseEntry.VideoFile }
            if (alreadyShown) {
                loading = false
                refreshing = false
                return
            }
            val root = LocalLibrary.loadRoot(frame.rootId)
            val rootPath = root?.let { LocalLibrary.rootPath(it) }
            val names = if (rootPath != null) {
                FolderGalleryIndex.loadLocalVideos(
                    frame.rootId,
                    LocalFolderListing.rootConfigKey(rootPath, frame.preferMediaStore),
                    frame.relativePath,
                    rootAbs = rootPath,
                )
            } else {
                null
            } ?: LocalLibrary.videoFileNamesInFolder(frame.rootId, frame.relativePath)
                ?: MediaStoreFs.videoFileNames(frame.path.toPath())
            if (!names.isNullOrEmpty()) {
                applyLocalVideoFolderFiles(frame, names)
                return
            }
        }
        if (!force && applyCachedLocalListing(frame) && isLocalListingSessionCurrent(frame)) {
            loading = false
            refreshing = false
            return
        }
        val haveListing = listedPath == targetPath && entries.isNotEmpty()
        loading = force || !haveListing
        error = null
        // Drop stale rows so we never paint child content under a parent path (or vice versa).
        // Also removes the Lazy list from composition so its DisposableEffect can save scroll
        // for the *leaving* path (not the destination).
        if (listedPath != targetPath) {
            entries = emptyList()
        }
        try {
            if (frame.isZipBrowse) {
                val root = LocalLibrary.loadRoot(frame.rootId)
                val rootPath = root?.let { LocalLibrary.rootPath(it) }
                val result = LocalFolderListing.listZipVirtualDirectory(
                    rootId = frame.rootId,
                    rootPath = rootPath,
                    zipPath = frame.path.toPath(),
                    zipRel = frame.relativePath,
                    inner = frame.zipInnerRel.orEmpty(),
                    currentDirName = frame.title,
                    preferMediaStore = frame.preferMediaStore,
                    useCache = !force,
                    photoGrid = frame.photoGrid,
                    onCached = { cached ->
                        if (stack.lastOrNull()?.let { frameListKey(it) } == targetPath) {
                            entries = cached
                            listedPath = targetPath
                            error = null
                            loading = false
                        }
                    },
                ) ?: error("Cannot read ZIP central directory")
                if (stack.lastOrNull()?.let { frameListKey(it) } != targetPath) return
                entries = result
                listedPath = targetPath
                error = null
                loading = false
                refreshing = false
                return
            }
            val root = LocalLibrary.loadRoot(frame.rootId)
            if (root == null) {
                error = "Missing library root"
                entries = emptyList()
                listedPath = targetPath
                loading = false
                refreshing = false
                return
            }
            val rootPath = LocalLibrary.rootPath(root)
            if (rootPath == null) {
                error = "Missing library root"
                entries = emptyList()
                listedPath = targetPath
                loading = false
                refreshing = false
                return
            }
            val result = LocalFolderListing.listDirectory(
                rootId = frame.rootId,
                rootPath = rootPath,
                relativeDir = frame.relativePath,
                listedPath = frame.path.toPath(),
                preferMediaStore = frame.preferMediaStore,
                useCache = !force,
                onCached = { cached ->
                    if (stack.lastOrNull()?.let { frameListKey(it) } == targetPath) {
                        entries = cached
                        listedPath = targetPath
                        error = null
                        loading = false
                        refreshing = true
                    }
                },
                onRefreshDone = {
                    if (stack.lastOrNull()?.let { frameListKey(it) } == targetPath) {
                        refreshing = false
                    }
                },
            )
            // Path changed mid-load — replacement reload owns spinner state.
            if (stack.lastOrNull()?.let { frameListKey(it) } != targetPath) return
            entries = result
            listedPath = targetPath
            error = null
            loading = false
            refreshing = LocalListingJobs.isActive(
                BrowseSession.localFolderListingKey(frame.rootId, frame.relativePath),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Path change / new reload owns loading — do not clear here (same as SMB).
            throw e
        } catch (e: Throwable) {
            if (stack.lastOrNull()?.let { frameListKey(it) } != targetPath) return
            error = e.message
            entries = emptyList()
            listedPath = targetPath
            loading = false
            refreshing = false
        }
    }

    /** Force the next stack-driven [reload] (zip-as-dir toggle leaving a zip frame). */
    var forceNextLoad by remember { mutableStateOf(false) }

    LaunchedEffect(stack) {
        if (skipNextListing && !forceNextLoad) {
            skipNextListing = false
            loading = false
            refreshing = false
            notifyLocalThumbFolder(stack.lastOrNull())
            return@LaunchedEffect
        }
        skipNextListing = false
        val force = forceNextLoad
        forceNextLoad = false
        reload(force = force)
    }

    DisposableEffect(Unit) {
        onDispose { notifyLocalThumbFolder(null) }
    }

    // Zip-as-dir toggle: force re-list in both directions so ArchiveGallery ↔ Folder/Directory
    // updates (toggle-on must parse zip CDs; cache cannot invent those rows). Same as SMB/WebDAV:
    // only on an actual setting change — first composition / return-from-reader remount must
    // not force-scan (that used to re-list every reader exit when the toggle was off).
    var prevZipAsDir by remember { mutableStateOf(browseZipAsDir) }
    LaunchedEffect(browseZipAsDir) {
        if (!ZipAsDirListing.zipAsDirToggleRequiresForceReload(prevZipAsDir, browseZipAsDir)) {
            return@LaunchedEffect
        }
        prevZipAsDir = browseZipAsDir
        if (stack.isEmpty()) return@LaunchedEffect
        if (!browseZipAsDir && stack.last().isZipBrowse) {
            forceNextLoad = true
            updateStack(stack.dropLastWhile { it.isZipBrowse })
            return@LaunchedEffect
        }
        reload(force = true)
    }

    // Turning Hidden files on: mark listing non-current so slim quick-scan deep-scans
    // shallow-tagged `.nomedia` / dot directories.
    var prevShowHidden by remember { mutableStateOf(showHiddenFiles) }
    LaunchedEffect(showHiddenFiles, stack) {
        if (showHiddenFiles && !prevShowHidden) {
            val frame = stack.lastOrNull()
            if (frame != null) {
                BrowseSession.getLocalFolderCachedListing(frame.rootId, frame.relativePath)
                    ?.let { cached ->
                        BrowseSession.putLocalFolderListing(
                            frame.rootId,
                            frame.relativePath,
                            cached.entries,
                            sessionCurrent = false,
                            pathAlias = resolveBrowsePath(
                                frame.path.toPath(),
                                preferMediaStore = frame.preferMediaStore,
                            ),
                        )
                    }
                reload(force = false)
            }
        }
        prevShowHidden = showHiddenFiles
    }

    fun enterRoot(root: LibraryRootEntity) {
        val path = LocalLibrary.rootPath(root) ?: return
        updateStack(
            listOf(
                BrowseSession.LocalFrame(
                    rootId = root.id,
                    path = path.toString(),
                    title = root.displayName,
                    relativePath = "",
                    preferMediaStore = root.prefersMediaStore,
                ),
            ),
        )
    }

    fun enterDir(
        entry: BrowseEntry.Directory,
        fromSearch: Boolean = false,
        keepVideoOverlay: Boolean = true,
    ) {
        val frame = stack.lastOrNull() ?: return
        if (fromSearch) {
            if (searchReturnStackSize < 0) searchReturnStackSize = stack.size
        } else {
            searchReturnStackSize = -1
        }
        val videoOverlay = keepVideoOverlay && frame.videoFolder
        if (frame.isZipBrowse) {
            val childInner = ZipAsDirListing.joinPrefix(
                frame.zipInnerRel.orEmpty(),
                entry.relativeName.ifEmpty { entry.name },
            )
            updateStack(
                stack + BrowseSession.LocalFrame(
                    rootId = frame.rootId,
                    path = frame.path,
                    title = entry.name,
                    relativePath = frame.relativePath,
                    preferMediaStore = frame.preferMediaStore,
                    videoFolder = videoOverlay,
                    zipInnerRel = childInner,
                ),
            )
            return
        }
        // Zip-as-dir: fake-folder Directory — path is the .zip/.cbz file.
        val zipSeg = if (browseZipAsDir) {
            ZipAsDirListing.zipFileSegment(entry.relativeName, entry.path.name)
        } else {
            null
        }
        if (zipSeg != null) {
            val zipRel = when {
                frame.relativePath.isEmpty() -> zipSeg
                else -> "${frame.relativePath.trimEnd('/')}/$zipSeg"
            }
            updateStack(
                stack + BrowseSession.LocalFrame(
                    rootId = frame.rootId,
                    path = entry.path.toString(),
                    title = entry.name,
                    relativePath = zipRel,
                    preferMediaStore = frame.preferMediaStore,
                    videoFolder = videoOverlay,
                    zipInnerRel = ZipAsDirListing.zipInnerPrefix(entry.relativeName),
                ),
            )
            return
        }
        // Real path segments (not virtual @display name) — same join as folderGalleryRelative.
        val child = entry.relativeName.replace('\\', '/').trim('/')
        val parent = frame.relativePath.replace('\\', '/').trim('/')
        val rel = when {
            child.isEmpty() -> parent
            parent.isEmpty() -> child
            else -> "$parent/$child"
        }
        updateStack(
            stack + BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = entry.path.toString(),
                title = entry.name,
                relativePath = rel,
                preferMediaStore = frame.preferMediaStore,
                videoFolder = videoOverlay,
            ),
        )
    }

    /** Path of [path] relative to the current listing, for overflow Open folder. */
    fun localOpenFolderRelative(path: okio.Path): String {
        val framePath = stack.lastOrNull()?.path ?: return path.name
        val base = framePath.replace('\\', '/').trimEnd('/')
        val full = path.toString().replace('\\', '/')
        if (base.isEmpty()) return full.trimStart('/')
        val prefix = "$base/"
        return if (full.startsWith(prefix)) full.removePrefix(prefix) else path.name
    }

    /** Overflow "Open folder". No-op when the target is already this listing. */
    fun openBrowseFolder(targetRel: String) {
        if (targetRel.isEmpty()) return
        searchReturnStackSize = -1
        search.close()
        if (searchFolderKey.isNotEmpty()) {
            BrowseSession.putFolderSearch(searchFolderKey, search.snapshot())
        }
        val frame = stack.lastOrNull() ?: return
        val path = if (frame.isZipBrowse) {
            frame.path.toPath()
        } else {
            frame.path.toPath().resolveRelative(targetRel)
        }
        enterDir(
            BrowseEntry.Directory(
                name = FolderSearch.baseName(targetRel),
                path = path,
                relativeName = targetRel,
                hasVideo = false,
                hasGallery = false,
                presence = DirPresence.Navigable,
            ),
            keepVideoOverlay = false,
        )
    }

    fun folderArchiveRelative(entry: BrowseEntry.ArchiveGallery, frame: BrowseSession.LocalFrame): String = when {
        frame.relativePath.isEmpty() -> entry.name
        else -> "${frame.relativePath.trimEnd('/')}/${entry.name}"
    }

    /** Push a ZIP/CBZ browse frame at archive root ([zipInnerRel] = ""). */
    fun enterZip(entry: BrowseEntry.ArchiveGallery) {
        val frame = stack.lastOrNull() ?: return
        val zipRel = folderArchiveRelative(entry, frame)
        updateStack(
            stack + BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = entry.path.toString(),
                title = entry.name,
                relativePath = zipRel,
                preferMediaStore = frame.preferMediaStore,
                zipInnerRel = "",
            ),
        )
    }

    fun goUp() {
        val originSize = searchReturnStackSize
        if (originSize >= 0 && stack.size > originSize) {
            searchReturnStackSize = -1
            if (originSize <= 0) {
                updateStack(emptyList())
                navigator.popBackStack()
            } else {
                updateStack(stack.take(originSize))
            }
            return
        }
        searchReturnStackSize = -1
        if (stack.size > 1) {
            updateStack(stack.dropLast(1))
        } else {
            // Leave folder browser back to Browse hub
            updateStack(emptyList())
            navigator.popBackStack()
        }
    }

    /** Same jump as the Back-to Browse/History/Library FAB. */
    fun jumpBackToOrigin() {
        when {
            fromHistory -> {
                if (!navigator.popBackStack(HistoryScreenDestination, inclusive = false)) {
                    navigator.navigate(HistoryScreenDestination) { launchSingleTop = true }
                }
            }
            fromLibrary -> {
                if (!navigator.popBackStack(LibraryScreenDestination, inclusive = false)) {
                    navigator.navigate(LibraryScreenDestination) { launchSingleTop = true }
                }
            }
            else -> {
                if (!navigator.popBackStack(BrowseScreenDestination, inclusive = false)) {
                    navigator.navigate(BrowseScreenDestination) { launchSingleTop = true }
                }
            }
        }
    }

    val hideBackToFab by Settings.hideBackToFab.collectAsState()
    fun onTopBarBack() {
        if (hideBackToFab) jumpBackToOrigin() else goUp()
    }

    BackHandler {
        // Search-section dir enter: first back returns to search, not close-search-in-child.
        if (searchReturnStackSize < 0 && search.handleBack { focusManager.clearFocus() }) {
            return@BackHandler
        }
        goUp()
    }

    /** History path link for the folder currently listed (parent of the opened file). */
    suspend fun recordCurrentBrowseFolderHistory() {
        val frame = stack.lastOrNull() ?: return
        val parentPath = stack.getOrNull(stack.lastIndex - 1)?.path
        val folderThumb = LocalHistory.localBrowseFolderThumbKey(
            rootId = frame.rootId,
            relativePath = frame.relativePath,
            currentPath = frame.path,
            entries = entries,
            parentPath = parentPath,
        )
        val histRel = if (frame.isZipBrowse) {
            ZipAsDirListing.virtualRelativeDir(frame.relativePath, frame.zipInnerRel.orEmpty())
        } else {
            frame.relativePath
        }
        LocalHistory.recordLocalBrowseFolder(
            rootId = frame.rootId,
            relativePath = histRel,
            title = frame.title,
            thumbKey = folderThumb,
        )
    }

    /**
     * History / reader relative path under the library root.
     * Uses [BrowseEntry.FolderGallery.relativeName] (real segments), never virtual `@…` [name]
     * — same join as SMB [SmbGateway.joinRelativePath] / WebDAV.
     */
    fun folderGalleryRelative(entry: BrowseEntry.FolderGallery, frame: BrowseSession.LocalFrame): String {
        if (frame.isZipBrowse) {
            // materializeLocal already stored gallery prefix under the zip in relativeName.
            return entry.relativeName.replace('\\', '/').trim('/')
        }
        val child = entry.relativeName.replace('\\', '/').trim('/')
        val parent = frame.relativePath.replace('\\', '/').trim('/')
        return when {
            child.isEmpty() -> parent
            parent.isEmpty() -> child
            else -> "$parent/$child"
        }
    }

    suspend fun localZipGalleryNames(
        frame: BrowseSession.LocalFrame,
        zipRel: String,
        inner: String,
        zipPath: Path,
    ): List<String> {
        val galleryDir = ZipAsDirListing.virtualRelativeDir(zipRel, inner)
        val root = LocalLibrary.loadRoot(frame.rootId)
        val rootPath = root?.let { LocalLibrary.rootPath(it) }
        if (rootPath != null) {
            FolderGalleryIndex.loadLocal(
                frame.rootId,
                LocalFolderListing.rootConfigKey(rootPath, frame.preferMediaStore),
                galleryDir,
            )?.let { return it }
        }
        return runCatching {
            withLocalZipCentralDirectory(zipPath) { cd ->
                ZipAsDirListing.directImageNames(cd, inner)
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Zip-as-dir FolderGallery on a parent FS listing: [relativeName] is `file.zip` or
     * `file.zip/Album` (promoted inner). Never join `parent/Album` as a real SAF folder.
     */
    fun isZipAsDirFolderGallery(entry: BrowseEntry.FolderGallery): Boolean {
        if (ZipAsDirListing.zipFileSegment(entry.relativeName, entry.path.name) != null) return true
        if (ZipPaths.isZipPath(entry.path.toString())) return true
        val cover = entry.coverPath?.toString()
        return !cover.isNullOrEmpty() && ZipPaths.isZipPath(cover)
    }

    /** Open a zip/cbz FolderGallery from the parent FS listing (flat root or promoted inner). */
    fun openZipFileAsRootGallery(
        entry: BrowseEntry.FolderGallery,
        frame: BrowseSession.LocalFrame,
        page: Int = -1,
    ) {
        val zipSeg = ZipAsDirListing.zipFileSegment(entry.relativeName, entry.path.name)
            ?: ZipPaths.parse(entry.path.toString())?.first?.toPath()?.name
            ?: ZipPaths.parse(entry.coverPath?.toString().orEmpty())?.first?.toPath()?.name
            ?: entry.path.name
        val inner = ZipAsDirListing.zipInnerPrefix(entry.relativeName)
        val zipRel = when {
            frame.relativePath.isEmpty() -> zipSeg
            else -> "${frame.relativePath.trimEnd('/')}/$zipSeg"
        }
        val zipPath = when {
            ZipPaths.parse(entry.path.toString()) != null ->
                ZipPaths.parse(entry.path.toString())!!.first
            isZipArchiveFileName(entry.path.name) -> entry.path.toString()
            else -> (frame.path.toPath() / zipSeg).toString()
        }
        val coverKey = entry.coverPath?.toString()
        val histRel = ZipAsDirListing.historyGalleryRelative(zipRel, inner)
        val gid = stableGalleryId(frame.rootId, "zip:$histRel")
        launchIO {
            val names = localZipGalleryNames(frame, zipRel, inner, zipPath.toPath())
            if (names.isEmpty()) {
                snackbar(context.getString(R.string.browse_open_failed))
                return@launchIO
            }
            val info = BaseGalleryInfo(
                gid = gid,
                token = LOCAL_FOLDER_TOKEN,
                title = entry.name,
                pages = names.size,
                favoriteSlot = NOT_FAVORITED,
                rating = -1f,
                thumbKey = coverKey,
                uploader = "${frame.rootId}\u0000$histRel",
                category = 0,
            )
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFolderGallery(
                rootId = frame.rootId,
                relativePath = histRel,
                title = entry.name,
                thumbKey = coverKey,
                pages = names.size,
                info = info,
            )
            withUIContext {
                ReaderGalleryPlaylist.setFromLocalBrowse(
                    rootId = frame.rootId,
                    parentPath = frame.path,
                    parentRelative = frame.relativePath,
                    entries = entries,
                )
                navToLocalZipFolderReader(
                    zipPath = zipPath,
                    innerRel = inner,
                    imageNames = names,
                    info = info,
                    page = page,
                )
            }
        }
    }

    fun openZipFolderGallery(
        entry: BrowseEntry.FolderGallery,
        frame: BrowseSession.LocalFrame,
        page: Int = -1,
    ) {
        val inner = entry.relativeName.replace('\\', '/').trim('/')
        val coverKey = entry.coverPath?.toString()
        val histRel = ZipAsDirListing.historyGalleryRelative(frame.relativePath, inner)
        val gid = stableGalleryId(frame.rootId, "zip:$histRel")
        launchIO {
            val names = localZipGalleryNames(frame, frame.relativePath, inner, frame.path.toPath())
            if (names.isEmpty()) {
                snackbar(context.getString(R.string.browse_open_failed))
                return@launchIO
            }
            val info = BaseGalleryInfo(
                gid = gid,
                token = LOCAL_FOLDER_TOKEN,
                title = entry.name,
                pages = names.size,
                favoriteSlot = NOT_FAVORITED,
                rating = -1f,
                thumbKey = coverKey,
                uploader = "${frame.rootId}\u0000$histRel",
                category = 0,
            )
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFolderGallery(
                rootId = frame.rootId,
                relativePath = histRel,
                title = entry.name,
                thumbKey = coverKey,
                pages = names.size,
                info = info,
            )
            withUIContext {
                navToLocalZipFolderReader(
                    zipPath = frame.path,
                    innerRel = inner,
                    imageNames = names,
                    info = info,
                    page = page,
                )
            }
        }
    }

    fun folderEntryProgressGid(entry: BrowseEntry.FolderGallery): Long {
        val frame = stack.lastOrNull() ?: return 0L
        if (frame.isZipBrowse) {
            val inner = entry.relativeName.replace('\\', '/').trim('/')
            val histRel = ZipAsDirListing.historyGalleryRelative(frame.relativePath, inner)
            return stableGalleryId(frame.rootId, "zip:$histRel")
        }
        if (browseZipAsDir && isZipAsDirFolderGallery(entry)) {
            val zipSeg = ZipAsDirListing.zipFileSegment(entry.relativeName, entry.path.name)
                ?: ZipPaths.parse(entry.path.toString())?.first?.toPath()?.name
                ?: ZipPaths.parse(entry.coverPath?.toString().orEmpty())?.first?.toPath()?.name
                ?: entry.path.name
            val inner = ZipAsDirListing.zipInnerPrefix(entry.relativeName)
            val zipRel = when {
                frame.relativePath.isEmpty() -> zipSeg
                else -> "${frame.relativePath.trimEnd('/')}/$zipSeg"
            }
            val histRel = ZipAsDirListing.historyGalleryRelative(zipRel, inner)
            return stableGalleryId(frame.rootId, "zip:$histRel")
        }
        val rel = folderGalleryRelative(entry, frame)
        return stableGalleryId(frame.rootId, rel.ifEmpty { "." })
    }

    fun openFolderGallery(entry: BrowseEntry.FolderGallery, page: Int = -1) {
        val frame = stack.lastOrNull() ?: return
        if (frame.isZipBrowse) {
            openZipFolderGallery(entry, frame, page)
            return
        }
        // Zip-as-dir FolderGallery in the parent listing (flat zip or promoted inner).
        // Detect via relativeName / zipfile cover, not only path basename — promoted
        // `@Album` rows must not save `parent/Album` as a real SAF folder.
        if (browseZipAsDir && isZipAsDirFolderGallery(entry)) {
            openZipFileAsRootGallery(entry, frame, page)
            return
        }
        // Playlist = gallery/archive rows in this browse list (lazy galleries), not only
        // path-parent siblings. When already inside a photo-grid overlay, siblings are
        // parent-list galleries if we came from a leaf enter; playlist still uses current
        // entries which is fine for single-gallery open.
        ReaderGalleryPlaylist.setFromLocalBrowse(
            rootId = frame.rootId,
            parentPath = frame.path,
            parentRelative = frame.relativePath,
            entries = entries,
        )
        val rel = folderGalleryRelative(entry, frame)
        val coverKey = entry.coverPath?.toString()
        val gid = stableGalleryId(frame.rootId, rel.ifEmpty { "." })
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_FOLDER_TOKEN,
            title = entry.name,
            pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = coverKey,
            uploader = "${frame.rootId}\u0000${rel.trim('/')}",
            category = 0,
        )
        launchIO {
            // Parent browse dir (not gated by file/gallery prefs) + gallery row.
            recordCurrentBrowseFolderHistory()
            // History = folder gallery (open → reader). Same gid as progress.
            LocalHistory.recordLocalFolderGallery(
                rootId = frame.rootId,
                relativePath = rel,
                title = entry.name,
                thumbKey = coverKey,
                pages = if (entry.pageCountCapped) 0 else entry.pageCount,
                info = info,
            )
        }
        val names = if (entry.pageCountCapped) {
            emptyList()
        } else {
            localPhotoGridNames(frame, rel).orEmpty()
        }
        navToLocalFolderReader(entry.path.toString(), info, page, names)
    }

    /**
     * Photo-grid virtual folder for a gallery. Pushes a photo-grid frame so back returns
     * to the parent listing (does not change global list/content mode).
     */
    fun openFolderGalleryPhotoGrid(entry: BrowseEntry.FolderGallery) {
        val frame = stack.lastOrNull() ?: return
        val newFrame = if (frame.isZipBrowse) {
            val inner = entry.relativeName.replace('\\', '/').trim('/')
            BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = frame.path,
                title = entry.name,
                relativePath = frame.relativePath,
                preferMediaStore = frame.preferMediaStore,
                photoGrid = true,
                zipInnerRel = inner,
            )
        } else if (browseZipAsDir && isZipAsDirFolderGallery(entry)) {
            val zipSeg = ZipAsDirListing.zipFileSegment(entry.relativeName, entry.path.name)
                ?: ZipPaths.parse(entry.path.toString())?.first?.toPath()?.name
                ?: ZipPaths.parse(entry.coverPath?.toString().orEmpty())?.first?.toPath()?.name
                ?: entry.path.name
            val zipRel = when {
                frame.relativePath.isEmpty() -> zipSeg
                else -> "${frame.relativePath.trimEnd('/')}/$zipSeg"
            }
            val zipPath = when {
                ZipPaths.parse(entry.path.toString()) != null ->
                    ZipPaths.parse(entry.path.toString())!!.first
                isZipArchiveFileName(entry.path.name) -> entry.path.toString()
                else -> (frame.path.toPath() / zipSeg).toString()
            }
            val inner = ZipAsDirListing.zipInnerPrefix(entry.relativeName)
            BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = zipPath,
                title = entry.name,
                relativePath = zipRel,
                preferMediaStore = frame.preferMediaStore,
                photoGrid = true,
                zipInnerRel = inner,
            )
        } else {
            val rel = folderGalleryRelative(entry, frame)
            BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = entry.path.toString(),
                title = entry.name,
                relativePath = rel,
                preferMediaStore = frame.preferMediaStore,
                photoGrid = true,
            )
        }
        val sameListing = frameListKey(newFrame) == listedPath &&
            entries.any { it is BrowseEntry.RegularFile && isImageFileName(it.name) }
        val galleryDir = if (newFrame.isZipBrowse) {
            ZipAsDirListing.virtualRelativeDir(newFrame.relativePath, newFrame.zipInnerRel.orEmpty())
        } else {
            newFrame.relativePath
        }
        val names = if (sameListing) null else localPhotoGridNames(frame, galleryDir)
        if (sameListing || names != null) {
            skipNextListing = true
        }
        updateStack(stack + newFrame)
        if (names != null) {
            applyLocalPhotoGridFiles(newFrame, names)
        }
    }

    /** Primary / secondary open for folder galleries based on [Settings.photoGridMode]. */
    fun openFolderGalleryPrimary(entry: BrowseEntry.FolderGallery) {
        if (photoGridMode) openFolderGalleryPhotoGrid(entry) else openFolderGallery(entry)
    }

    fun openFolderGallerySecondary(entry: BrowseEntry.FolderGallery) {
        if (photoGridMode) openFolderGallery(entry) else openFolderGalleryPhotoGrid(entry)
    }

    fun openNestedFolderImage(
        frame: BrowseSession.LocalFrame,
        parentRel: String,
        fileName: String,
        file: BrowseEntry.RegularFile,
    ) {
        launchIO {
            if (frame.isZipBrowse) {
                val inner = ZipAsDirListing.joinPrefix(frame.zipInnerRel.orEmpty(), parentRel)
                val names = localZipGalleryNames(
                    frame,
                    frame.relativePath,
                    inner,
                    frame.path.toPath(),
                ).ifEmpty {
                    withLocalZipCentralDirectory(frame.path.toPath()) { cd ->
                        ZipAsDirListing.directImageNames(cd, inner)
                    }.orEmpty()
                }
                if (names.isEmpty()) return@launchIO
                val page = names.indexOfFirst { it.equals(fileName, ignoreCase = true) }
                    .coerceAtLeast(0)
                val histRel = ZipAsDirListing.historyGalleryRelative(frame.relativePath, inner)
                val coverKey = ZipPaths.encodePath(
                    frame.path,
                    ZipAsDirListing.joinPrefix(inner, names.first()),
                ).toString()
                val galleryTitle = FolderSearch.baseName(parentRel).ifEmpty { frame.title }
                val gid = stableGalleryId(frame.rootId, "zip:$histRel")
                val info = BaseGalleryInfo(
                    gid = gid,
                    token = LOCAL_FOLDER_TOKEN,
                    title = galleryTitle,
                    pages = names.size,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = coverKey,
                    uploader = "${frame.rootId}\u0000$histRel",
                    category = 0,
                )
                recordCurrentBrowseFolderHistory()
                LocalHistory.recordLocalFolderGallery(
                    rootId = frame.rootId,
                    relativePath = histRel,
                    title = galleryTitle,
                    thumbKey = coverKey,
                    pages = names.size,
                    info = info,
                )
                withUIContext {
                    navToLocalZipFolderReader(
                        zipPath = frame.path,
                        innerRel = inner,
                        imageNames = names,
                        info = info,
                        page = page,
                    )
                }
                return@launchIO
            }
            val galleryRel = when {
                parentRel.isEmpty() -> frame.relativePath
                frame.relativePath.isEmpty() -> parentRel
                else -> "${frame.relativePath.trimEnd('/')}/$parentRel"
            }
            val parentPath = file.path.parent ?: frame.path.toPath().resolveRelative(parentRel)
            val names = localPhotoGridNames(frame, galleryRel)
                ?: parentPath.listBrowseChildrenRaw()
                    .filter { !it.isDirectory && isImageFileName(it.name) }
                    .map { it.name }
                    .sortedWith { a, b -> naturalCompare(a, b) }
            if (names.isEmpty()) return@launchIO
            val page = names.indexOfFirst { it.equals(fileName, ignoreCase = true) }
                .coerceAtLeast(0)
            val coverKey = (parentPath / names.first()).toString()
            val galleryTitle = FolderSearch.baseName(parentRel).ifEmpty { frame.title }
            val gid = stableGalleryId(frame.rootId, galleryRel.ifEmpty { "." })
            val info = BaseGalleryInfo(
                gid = gid,
                token = LOCAL_FOLDER_TOKEN,
                title = galleryTitle,
                pages = names.size,
                favoriteSlot = NOT_FAVORITED,
                rating = -1f,
                thumbKey = coverKey,
                uploader = "${frame.rootId}\u0000${galleryRel.trim('/')}",
                category = 0,
            )
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFolderGallery(
                rootId = frame.rootId,
                relativePath = galleryRel,
                title = galleryTitle,
                thumbKey = coverKey,
                pages = names.size,
                info = info,
            )
            withUIContext {
                navToLocalFolderReader(parentPath.toString(), info, page, names)
            }
        }
    }

    /**
     * Tap an image (photo-grid virtual folder **or** Folder-mode file row) → reader at that page.
     * Same page list / cover keys as the photo-grid path.
     */
    fun openFolderImage(file: BrowseEntry.RegularFile) {
        val frame = stack.lastOrNull() ?: return
        val rel = file.name.replace('\\', '/').trim('/')
        val fileName = FolderSearch.baseName(rel).ifEmpty { file.path.name }
        if (!isImageFileName(fileName)) return
        val parentRel = FolderSearch.parentRelative(rel)
        val inListing = parentRel.isEmpty() && folderImages.any { it.path == file.path }
        if (!inListing) {
            openNestedFolderImage(frame, parentRel, fileName, file)
            return
        }
        val images = folderImages
        val page = images.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        if (frame.isZipBrowse) {
            val inner = frame.zipInnerRel.orEmpty()
            val names = images.map { it.name }
            val coverKey = images.firstOrNull()?.path?.toString()
            val histRel = ZipAsDirListing.historyGalleryRelative(frame.relativePath, inner)
            val gid = stableGalleryId(frame.rootId, "zip:$histRel")
            val info = BaseGalleryInfo(
                gid = gid,
                token = LOCAL_FOLDER_TOKEN,
                title = frame.title,
                pages = names.size,
                favoriteSlot = NOT_FAVORITED,
                rating = -1f,
                thumbKey = coverKey,
                uploader = "${frame.rootId}\u0000$histRel",
                category = 0,
            )
            launchIO {
                recordCurrentBrowseFolderHistory()
                LocalHistory.recordLocalFolderGallery(
                    rootId = frame.rootId,
                    relativePath = histRel,
                    title = frame.title,
                    thumbKey = coverKey,
                    pages = names.size,
                    info = info,
                )
                withUIContext {
                    navToLocalZipFolderReader(
                        zipPath = frame.path,
                        innerRel = inner,
                        imageNames = names,
                        info = info,
                        page = page,
                    )
                }
            }
            return
        }
        val coverKey = images.firstOrNull()?.path?.toString()
        val gid = stableGalleryId(frame.rootId, frame.relativePath.ifEmpty { "." })
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_FOLDER_TOKEN,
            title = frame.title,
            pages = images.size,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = coverKey,
            uploader = "${frame.rootId}\u0000${frame.relativePath.trim('/')}",
            category = 0,
        )
        launchIO {
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFolderGallery(
                rootId = frame.rootId,
                relativePath = frame.relativePath,
                title = frame.title,
                thumbKey = coverKey,
                pages = images.size,
                info = info,
            )
        }
        // Parent playlist: use gallery path as single-item context (images are pages).
        ReaderGalleryPlaylist.setFromLocalBrowse(
            rootId = frame.rootId,
            parentPath = frame.path,
            parentRelative = frame.relativePath,
            entries = entries,
        )
        navToLocalFolderReader(frame.path, info, page, images.map { it.name })
    }

    fun openArchiveReader(entry: BrowseEntry.ArchiveGallery, skipPdfPrimary: Boolean = false) {
        val frame = stack.lastOrNull()
        if (frame != null && !frame.isZipBrowse) {
            ReaderGalleryPlaylist.setFromLocalBrowse(
                rootId = frame.rootId,
                parentPath = frame.path,
                parentRelative = frame.relativePath,
                entries = entries,
            )
        }
        val path = entry.path.toString()
        launchIO {
            // Parent browse dir (not gated by file/gallery prefs) + file row.
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalArchive(path, title = entry.name)
        }
        navToReader(path, skipPdfPrimary = skipPdfPrimary)
    }

    fun openPdfInOtherApp(entry: BrowseEntry.ArchiveGallery, usePreferredReader: Boolean = true) {
        if (!isPdfFileName(entry.name)) return
        val path = entry.path.toString()
        launchIO {
            // Parent dir + file row (non-dir open).
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFile(path, title = entry.name)
            try {
                OpenPdfExternally.openLocal(
                    context,
                    path,
                    displayName = entry.name,
                    usePreferredReader = usePreferredReader,
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(
                        R.string.open_pdf_external_failed,
                        e.message ?: e.toString(),
                    ),
                )
            }
        }
    }

    fun openPdfReader(entry: BrowseEntry.ArchiveGallery) {
        if (!isPdfFileName(entry.name)) return
        val path = entry.path.toString()
        launchIO {
            recordCurrentBrowseFolderHistory()
            val info = LocalHistory.galleryInfoForLocalArchive(path, title = entry.name)
            LocalHistory.ensureGalleryForProgress(info)
            LocalHistory.recordLocalArchive(path, title = entry.name)
            val page = runCatching { EhDB.getReadProgress(info.gid) }.getOrDefault(0)
            try {
                OpenPdfExternally.openInternalLocal(
                    context,
                    path,
                    displayName = entry.name,
                    progressGid = info.gid,
                    startPage = page,
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(
                        R.string.pdf_reader_open_failed,
                        e.message ?: e.toString(),
                    ),
                )
            }
        }
    }

    fun openPdfPrimary(entry: BrowseEntry.ArchiveGallery) {
        when (Settings.pdfReaderMode.value) {
            PdfReaderMode.PDF -> openPdfReader(entry)
            PdfReaderMode.EXTERNAL -> openPdfInOtherApp(entry)
            else -> openArchiveReader(entry)
        }
    }

    fun openPdfSecondary(entry: BrowseEntry.ArchiveGallery) {
        when (Settings.pdfReaderMode.value) {
            PdfReaderMode.PDF, PdfReaderMode.EXTERNAL -> openArchiveReader(entry, skipPdfPrimary = true)
            else -> openPdfReader(entry)
        }
    }

    /**
     * Long-press archive (zip/rar/7z/…) → system "Open with" picker.
     * Tap still opens the in-app reader. PDF uses [openPdfInOtherApp].
     */
    fun openArchiveInOtherApp(entry: BrowseEntry.ArchiveGallery) {
        if (isPdfFileName(entry.name)) {
            openPdfInOtherApp(entry)
            return
        }
        val path = entry.path.toString()
        val name = entry.name
        launchIO {
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFile(path, title = name)
            try {
                OpenFileExternally.openLocal(
                    context,
                    path,
                    displayName = name,
                    mimeType = mimeTypeForFileName(name),
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) +
                        " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun openArchiveSecondary(entry: BrowseEntry.ArchiveGallery) {
        if (isPdfFileName(entry.name)) {
            openPdfSecondary(entry)
        } else {
            openArchiveInOtherApp(entry)
        }
    }

    fun openArchive(entry: BrowseEntry.ArchiveGallery) {
        if (isPdfFileName(entry.name)) {
            openPdfPrimary(entry)
            return
        }
        val frame = stack.lastOrNull()
        // Nested archive inside a ZIP — stub in v1.
        if (frame?.isZipBrowse == true || ZipPaths.isZipPath(entry.path.toString())) {
            launchIO {
                snackbar(context.getString(R.string.zip_nested_archive_stub))
            }
            return
        }
        // Zip-as-dir: always enter as a virtual folder (classify via DirectoryListing).
        // Flat CBZ roots show as FolderGallery like a normal image folder.
        if (browseZipAsDir && isZipArchiveFileName(entry.name)) {
            enterZip(entry)
            return
        }
        openArchiveReader(entry)
    }

    fun openExternalFile(path: okio.Path, asFile: Boolean = false, usePreferredPlayer: Boolean = true) {
        // Always launch with the real path basename — promoted VideoFile rows use a
        // virtual `@dir` display name without extension (wrong MIME / player title).
        val pathStr = path.toString()
        val actualName = ZipPaths.memberLeafName(pathStr) ?: path.name
        launchIO {
            // Parent dir + file/video row (non-dir open).
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFile(pathStr, title = actualName)
            try {
                OpenFileExternally.openLocal(
                    context,
                    pathStr,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    asFile = asFile,
                    usePreferredPlayer = usePreferredPlayer,
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(
                        R.string.browse_open_failed,
                    ) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun openLocalHtml(path: okio.Path, incognito: Boolean) {
        val pathStr = path.toString()
        val actualName = ZipPaths.memberLeafName(pathStr) ?: path.name
        launchIO {
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFile(pathStr, title = actualName)
            try {
                OpenFileExternally.openLocalHtml(
                    context = context,
                    pathStr = pathStr,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    incognito = incognito,
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun copyLocalHtmlUrl(path: okio.Path) {
        val pathStr = path.toString()
        val actualName = ZipPaths.memberLeafName(pathStr) ?: path.name
        launchIO {
            try {
                val uri = OpenFileExternally.ensureLocalHtmlHttpUri(
                    pathStr = pathStr,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                )
                withUIContext {
                    with(context) { addTextToClipboard(uri.toString()) }
                }
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    /** In-app Media3 player. */
    fun playVideo(path: okio.Path) {
        val pathStr = path.toString()
        val actualName = ZipPaths.memberLeafName(pathStr) ?: path.name
        launchIO {
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFile(pathStr, title = actualName)
            try {
                OpenFileExternally.playLocal(
                    context,
                    pathStr,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    playlistPaths = entries
                        .filterIsInstance<BrowseEntry.VideoFile>()
                        .map { it.path.toString() },
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(
                        R.string.browse_open_failed,
                    ) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    /** Primary action: Media3 when [Settings.useMedia3Player] is on, else external. */
    fun openVideoPrimary(path: okio.Path) {
        if (Settings.useMedia3Player.value) playVideo(path) else openExternalFile(path)
    }

    /** Long-press: opposite of [openVideoPrimary]. */
    fun openVideoSecondary(path: okio.Path) {
        if (Settings.useMedia3Player.value) openExternalFile(path) else playVideo(path)
    }

    fun notSupportedAction() {
        launch { snackbar(context.getString(R.string.browse_action_not_supported)) }
    }

    fun toggleFolderGalleryFavorite(entry: BrowseEntry.FolderGallery) {
        val frame = stack.lastOrNull() ?: return
        val rel = folderGalleryRelative(entry, frame)
        BrowseFavorites.toggleLocalFolder(frame.rootId, rel, thumbKey = entry.coverPath?.toString())
    }

    fun isFolderGalleryFavorite(entry: BrowseEntry.FolderGallery): Boolean {
        val frame = stack.lastOrNull() ?: return false
        val rel = folderGalleryRelative(entry, frame)
        return BrowseFavorites.localFolderKey(frame.rootId, rel) in favoriteKeys
    }

    fun copyLocalVideoUrl(path: okio.Path) {
        val pathStr = path.toString()
        val actualName = ZipPaths.memberLeafName(pathStr) ?: path.name
        launchIO {
            try {
                val uri = OpenFileExternally.ensureLocalVideoHttpUri(
                    pathStr = pathStr,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                )
                withUIContext {
                    with(context) { addTextToClipboard(uri.toString()) }
                }
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun saveLocalFile(path: okio.Path) {
        val name = ZipPaths.memberLeafName(path.toString()) ?: path.name
        launchIO { with(context) { BrowseSaveAs.saveLocalFile(path, name) } }
    }

    fun shareLocalFile(path: okio.Path) {
        val name = ZipPaths.memberLeafName(path.toString()) ?: path.name
        launchIO { with(context) { BrowseSaveAs.shareLocalFile(path, name) } }
    }

    fun shareLocalViaHttp(block: suspend () -> HttpShareItem) {
        launchIO {
            try {
                val item = block()
                withUIContext { awaitHttpShareQr(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_http_share_failed) + " " +
                        (e.message ?: e.toString()),
                )
            }
        }
    }

    fun localHttpShareFile(path: okio.Path): () -> Unit {
        val pathStr = path.toString()
        val name = ZipPaths.memberLeafName(pathStr) ?: path.name
        return {
            shareLocalViaHttp {
                HttpShare.startLocalFile(context, pathStr, name, mimeTypeForFileName(name))
            }
        }
    }

    fun localHttpShareFolder(dir: okio.Path, displayName: String, relativeName: String): () -> Unit {
        val leaf = relativeName.substringAfterLast('/').ifEmpty { displayName }
        // Zip-as-dir rows are not real directories; share the zip file like Open / Share.
        if (stack.lastOrNull()?.isZipBrowse == true ||
            isZipArchiveFileName(leaf) ||
            isZipArchiveFileName(dir.name)
        ) {
            return localHttpShareFile(dir)
        }
        return {
            shareLocalViaHttp {
                HttpShare.startLocalFolder(context, dir.toString(), displayName)
            }
        }
    }

    fun saveLocalFolder(dir: okio.Path, displayName: String, relativeName: String) {
        val name = relativeName.substringAfterLast('/').ifEmpty { displayName }
        launchIO { with(context) { BrowseSaveAs.saveLocalFolder(dir, name, relativeName) } }
    }

    fun dirOverflow(dir: BrowseEntry.Directory) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Common,
        favorited = isDirFavorite(dir),
        onFavorite = { toggleDirFavorite(dir) },
        onSaveAs = { saveLocalFolder(dir.path, dir.name, dir.relativeName) },
        onShareViaHttp = localHttpShareFolder(dir.path, dir.name, dir.relativeName),
        onOpenFolder = {
            openBrowseFolder(
                FolderSearch.openFolderTarget(
                    dir.relativeName,
                    isDirectory = true,
                    virtual = dir.virtual,
                ),
            )
        },
        onUnsupported = { notSupportedAction() },
    )

    fun folderGalleryOverflow(entry: BrowseEntry.FolderGallery) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Gallery,
        favorited = isFolderGalleryFavorite(entry),
        onFavorite = { toggleFolderGalleryFavorite(entry) },
        onRead = { openFolderGallery(entry) },
        onPhotoGrid = { openFolderGalleryPhotoGrid(entry) },
        onSaveAs = { saveLocalFolder(entry.path, entry.name, entry.relativeName) },
        onShareViaHttp = localHttpShareFolder(entry.path, entry.name, entry.relativeName),
        onOpenFolder = {
            openBrowseFolder(
                FolderSearch.openFolderTarget(
                    entry.relativeName,
                    isDirectory = true,
                    virtual = entry.virtual,
                ),
            )
        },
        onUnsupported = { notSupportedAction() },
    )

    fun archiveOverflow(entry: BrowseEntry.ArchiveGallery) = if (isPdfFileName(entry.name)) {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Pdf,
            onRead = { openArchiveReader(entry, skipPdfPrimary = true) },
            onPlay = { openPdfReader(entry) },
            onExternalPlayer = { openPdfInOtherApp(entry) },
            onOpenWith = { openPdfInOtherApp(entry, usePreferredReader = false) },
            onSaveAs = { saveLocalFile(entry.path) },
            onShare = { shareLocalFile(entry.path) },
            onShareViaHttp = localHttpShareFile(entry.path),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(entry.name, isDirectory = false))
            },
            onUnsupported = { notSupportedAction() },
        )
    } else {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Gallery,
            onRead = { openArchive(entry) },
            onOpenWith = { openArchiveInOtherApp(entry) },
            onSaveAs = { saveLocalFile(entry.path) },
            onShare = { shareLocalFile(entry.path) },
            onShareViaHttp = localHttpShareFile(entry.path),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(entry.name, isDirectory = false))
            },
            onUnsupported = { notSupportedAction() },
        )
    }

    fun videoOverflow(
        path: okio.Path,
        relativeName: String = localOpenFolderRelative(path),
        virtual: Boolean = false,
    ) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Video,
        onPlay = { playVideo(path) },
        onExternalPlayer = { openExternalFile(path) },
        onCopyUrl = { copyLocalVideoUrl(path) },
        onOpenWith = { openExternalFile(path, usePreferredPlayer = false) },
        onSaveAs = { saveLocalFile(path) },
        onShare = { shareLocalFile(path) },
        onShareViaHttp = localHttpShareFile(path),
        onOpenFolder = {
            openBrowseFolder(
                FolderSearch.openFolderTarget(
                    relativeName,
                    isDirectory = false,
                    virtual = virtual,
                ),
            )
        },
        onUnsupported = { notSupportedAction() },
    )

    fun fileOverflow(path: okio.Path, relativeName: String = path.name) = if (isHtmlFileName(path.name)) {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Webpage,
            onOpenInBrowser = { openLocalHtml(path, incognito = false) },
            onOpenIncognito = { openLocalHtml(path, incognito = true) },
            onCopyUrl = { copyLocalHtmlUrl(path) },
            onOpenWith = { openExternalFile(path, asFile = true) },
            onSaveAs = { saveLocalFile(path) },
            onShare = { shareLocalFile(path) },
            onShareViaHttp = localHttpShareFile(path),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(relativeName, isDirectory = false))
            },
            onUnsupported = { notSupportedAction() },
        )
    } else {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Common,
            onOpenWith = { openExternalFile(path) },
            onSaveAs = { saveLocalFile(path) },
            onShare = { shareLocalFile(path) },
            onShareViaHttp = localHttpShareFile(path),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(relativeName, isDirectory = false))
            },
            onUnsupported = { notSupportedAction() },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (search.active) {
                        BrowseTopBarSearchField(state = search, hint = searchHint)
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = { onTopBarBack() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    BrowseTopBarSearchAction(
                        state = search,
                        onBeforeClose = { focusManager.clearFocus() },
                    )
                    BrowseViewModeMenu(
                        folder = if (virtual.isVirtual) null else folderId,
                        hideContentModes = virtual.hideContentModes,
                    )
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
            // Settings → Hide Back-to FAB: hide and map top-bar back to the same jump.
            // Visibility follows enterAlways top-bar scroll (same collapsedFraction).
            if (LocalShowNavShortcutFab.current && !hideBackToFab) {
                AnimatedVisibility(
                    visible = showScrollFab,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    when {
                        fromHistory -> ExtendedFloatingActionButton(
                            onClick = { jumpBackToOrigin() },
                            icon = {
                                Icon(Icons.Default.History, contentDescription = null)
                            },
                            text = { Text(stringResource(R.string.back_to_history)) },
                        )
                        fromLibrary -> ExtendedFloatingActionButton(
                            onClick = { jumpBackToOrigin() },
                            icon = {
                                Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null)
                            },
                            text = { Text(stringResource(R.string.back_to_library)) },
                        )
                        else -> ExtendedFloatingActionButton(
                            onClick = { jumpBackToOrigin() },
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
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .browseSearchClearFocusOnInteract(search),
        ) {
            when {
                stack.isEmpty() -> {
                    // Should open from Browse with a pre-selected root; show fallback if not
                    if (roots.isEmpty()) {
                        BrowseEmptyHint(stringResource(R.string.folder_no_roots))
                    } else {
                        FastScrollLazyVerticalGrid(
                            columns = GalleryGridDefaults.listColumns(),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(roots, key = { it.id }) { root ->
                                BrowseDirectoryRow(
                                    name = root.displayName,
                                    onClick = { enterRoot(root) },
                                )
                            }
                        }
                    }
                }
                loading && (displayEntries.isEmpty() || listedPath != currentPath) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                error != null && displayEntries.isEmpty() -> {
                    BrowseEmptyHint(error!!)
                }
                displayEntries.isEmpty() && searchHits.isEmpty() && !searching -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                filteredEntries.isEmpty() && searchHits.isEmpty() && !searching &&
                    search.submittedKeyword.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    // List only composes when this path's entries are ready. State is keyed by
                    // path+layout so parent/child never share one LazyList scroll index.
                    val pathKey = (listedPath ?: currentPath!!) + when {
                        photoGrid -> "#pg"
                        videoFolder -> "#vf"
                        else -> ""
                    }
                    val favoritesOnTop by Settings.browseFavoritesOnTop.collectAsState()
                    val browseSortModePref by Settings.browseSortMode.collectAsState()
                    val browseSortMode = BrowseSortMode.fromPref(browseSortModePref)
                    val browseSortAscending by Settings.browseSortAscending.collectAsState()
                    val sections = filteredEntries.toBrowseSections()
                    // UI-only order; DirectoryListing / folderImages / open-gallery stay name-sorted.
                    val dirsRaw = sections.directories
                        .filterIsInstance<BrowseEntry.Directory>()
                        .sortedForBrowseFolderUi(
                            browseSortMode,
                            browseSortAscending,
                            nameOf = { it.name },
                            dateOf = { it.lastModifiedMs },
                        )
                    val dirs = if (favoritesOnTop) {
                        val (fav, rest) = dirsRaw.partition { isDirFavorite(it) }
                        fav + rest
                    } else {
                        dirsRaw
                    }
                    val galleries = sections.galleries.sortedForBrowseFolderUi(
                        browseSortMode,
                        browseSortAscending,
                        nameOf = { it.name },
                        dateOf = { it.lastModifiedMs },
                    )
                    val videos = sections.videos
                        .filterIsInstance<BrowseEntry.VideoFile>()
                        .sortedForBrowseFolderUi(
                            browseSortMode,
                            browseSortAscending,
                            nameOf = { it.name },
                            dateOf = { it.lastModifiedMs },
                        )
                    val files = sections.files
                        .filterIsInstance<BrowseEntry.RegularFile>()
                        .sortedForBrowseFolderUi(
                            browseSortMode,
                            browseSortAscending,
                            nameOf = { it.name },
                            dateOf = { it.lastModifiedMs },
                        )
                    // In-memory only; resets when path/layout key changes. No prefs.
                    val animateItems by Settings.animateItems.collectAsState()
                    val (collapsedSections, toggleSection) = rememberBrowseSectionCollapse(pathKey)
                    fun searchHitKey(entry: BrowseEntry): String = when (entry) {
                        is BrowseEntry.Directory -> "d-${entry.path}|${entry.relativeName}"
                        is BrowseEntry.FolderGallery -> "g-${entry.path}|${entry.relativeName}"
                        is BrowseEntry.ArchiveGallery -> "a-${entry.path}"
                        is BrowseEntry.VideoFile -> "v-${entry.path}"
                        is BrowseEntry.RegularFile -> "f-${entry.path}"
                    }
                    fun LazyGridScope.searchSection(grid: Boolean) {
                        if (search.submittedKeyword.isEmpty() && !searching) return
                        item(key = "hdr-search", span = { GridItemSpan(maxLineSpan) }) {
                            BrowseSearchSectionHeader(
                                searching = searching,
                                onClick = { toggleSection(BrowseFolderSection.Search) },
                            )
                        }
                        if (BrowseFolderSection.Search in collapsedSections) return
                        if (searchHits.isEmpty()) {
                            item(key = "search-status", span = { GridItemSpan(maxLineSpan) }) {
                                if (searching) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularWavyProgressIndicator()
                                    }
                                } else {
                                    BrowseEmptyHint(stringResource(R.string.browse_search_empty))
                                }
                            }
                            return
                        }
                        items(searchHits, key = { "s-${searchHitKey(it)}" }) { entry ->
                            val itemMod = Modifier.thenIf(animateItems) { animateItem() }
                            when (entry) {
                                is BrowseEntry.Directory -> if (grid) {
                                    BrowseDirectoryGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        onClick = { enterDir(entry, fromSearch = true) },
                                        onLongClick = { toggleDirFavorite(entry) },
                                        showFavoriteStar = isDirFavorite(entry),
                                        cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                        showFolderThumb = browseFolderThumbs,
                                        overflow = dirOverflow(entry),
                                    )
                                } else {
                                    BrowseDirectoryRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        onClick = { enterDir(entry, fromSearch = true) },
                                        onLongClick = { toggleDirFavorite(entry) },
                                        cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                        showFolderThumb = browseFolderThumbs,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        sizeBytes = entry.size,
                                        typeLabel = browseZipAsDirTypeLabel(
                                            entry.relativeName,
                                            entry.name,
                                        ) ?: "Dir",
                                        overflow = dirOverflow(entry),
                                        showFavoriteStar = isDirFavorite(entry),
                                    )
                                }
                                is BrowseEntry.FolderGallery -> if (grid) {
                                    BrowseFolderGalleryGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        pageCount = entry.pageCount,
                                        pageCountCapped = entry.pageCountCapped,
                                        cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                        progressGid = folderEntryProgressGid(entry),
                                        showPages = showGalleryPages,
                                        onClick = { openFolderGalleryPrimary(entry) },
                                        onLongClick = { openFolderGallerySecondary(entry) },
                                        overflow = folderGalleryOverflow(entry),
                                    )
                                } else {
                                    BrowseFolderGalleryRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        pageCount = entry.pageCount,
                                        pageCountCapped = entry.pageCountCapped,
                                        cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                        progressGid = folderEntryProgressGid(entry),
                                        showPages = showGalleryPages,
                                        onClick = { openFolderGalleryPrimary(entry) },
                                        onLongClick = { openFolderGallerySecondary(entry) },
                                        lastModifiedMs = entry.lastModifiedMs,
                                        sizeBytes = entry.size,
                                        typeLabel = browseZipAsDirTypeLabel(
                                            entry.relativeName,
                                            entry.name,
                                        ) ?: "Folder",
                                        overflow = folderGalleryOverflow(entry),
                                    )
                                }
                                is BrowseEntry.ArchiveGallery -> if (grid) {
                                    BrowseArchiveGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        cover = BrowseCover.LocalArchive(entry.path),
                                        onClick = { openArchive(entry) },
                                        onLongClick = { openArchiveSecondary(entry) },
                                        pageCount = entry.pageCount,
                                        showPages = showGalleryPages,
                                        overflow = archiveOverflow(entry),
                                    )
                                } else {
                                    BrowseArchiveGalleryRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        cover = BrowseCover.LocalArchive(entry.path),
                                        onClick = { openArchive(entry) },
                                        onLongClick = { openArchiveSecondary(entry) },
                                        fileName = entry.name,
                                        sizeBytes = entry.size,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        pageCount = entry.pageCount,
                                        showPages = showGalleryPages,
                                        overflow = archiveOverflow(entry),
                                    )
                                }
                                is BrowseEntry.VideoFile -> if (grid) {
                                    BrowseVideoGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        thumbnailSource = VideoThumbnailSource.Local(
                                            path = entry.path.toString(),
                                            knownSizeBytes = entry.size,
                                        ),
                                        onClick = { openVideoPrimary(entry.path) },
                                        onLongClick = { openVideoSecondary(entry.path) },
                                        overflow = videoOverflow(entry.path, virtual = entry.virtual),
                                    )
                                } else {
                                    BrowseVideoRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        thumbnailSource = VideoThumbnailSource.Local(
                                            path = entry.path.toString(),
                                            knownSizeBytes = entry.size,
                                        ),
                                        onClick = { openVideoPrimary(entry.path) },
                                        onLongClick = { openVideoSecondary(entry.path) },
                                        fileName = entry.name,
                                        sizeBytes = entry.size,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        overflow = videoOverflow(entry.path, virtual = entry.virtual),
                                    )
                                }
                                is BrowseEntry.RegularFile -> {
                                    val isImage = isImageFileName(entry.name.substringAfterLast('/'))
                                    if (grid) {
                                        if (isImage) {
                                            BrowsePhotoGridImageItem(
                                                modifier = itemMod,
                                                name = entry.name,
                                                cover = BrowseCover.Local(entry.path),
                                                showPhotoThumb = true,
                                                onClick = { openFolderImage(entry) },
                                                onLongClick = { openExternalFile(entry.path) },
                                                overflow = fileOverflow(entry.path, entry.name),
                                            )
                                        } else {
                                            BrowseFileGridItem(
                                                modifier = itemMod,
                                                name = entry.name,
                                                onClick = { openExternalFile(entry.path) },
                                                onLongClick = { openExternalFile(entry.path) },
                                                overflow = fileOverflow(entry.path, entry.name),
                                            )
                                        }
                                    } else {
                                        BrowseFileRow(
                                            modifier = itemMod,
                                            name = entry.name,
                                            cover = if (isImage) BrowseCover.Local(entry.path) else null,
                                            showPhotoThumb = isImage,
                                            onClick = {
                                                if (isImage) {
                                                    openFolderImage(entry)
                                                } else {
                                                    openExternalFile(entry.path)
                                                }
                                            },
                                            onLongClick = { openExternalFile(entry.path) },
                                            fileName = entry.name,
                                            sizeBytes = entry.size,
                                            lastModifiedMs = entry.lastModifiedMs,
                                            overflow = fileOverflow(entry.path, entry.name),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (photoGrid) {
                        // Virtual image-only grid for a folder gallery (long-press).
                        val frame = stack.lastOrNull()
                        val progressGid = frame?.let {
                            if (it.isZipBrowse) {
                                val histRel = ZipAsDirListing.historyGalleryRelative(
                                    it.relativePath,
                                    it.zipInnerRel.orEmpty(),
                                )
                                stableGalleryId(it.rootId, "zip:$histRel")
                            } else {
                                stableGalleryId(it.rootId, it.relativePath.ifEmpty { "." })
                            }
                        } ?: 0L
                        val gridState = rememberLocalPhotoGridState(
                            pathKey = pathKey,
                            listMode = scrollLayoutKey,
                            progressGid = progressGid,
                            imageCount = folderImages.size,
                        )
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
                            searchSection(grid = true)
                            items(folderImages, key = { "pg-${it.path}" }) { file ->
                                BrowsePhotoGridImageItem(
                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                    name = file.name,
                                    cover = BrowseCover.Local(file.path),
                                    showPhotoThumb = true,
                                    onClick = { openFolderImage(file) },
                                    onLongClick = { openExternalFile(file.path) },
                                    overflow = fileOverflow(file.path),
                                )
                            }
                        }
                    } else if (useGrid) {
                        val gridState = rememberBrowseGridState(pathKey, scrollLayoutKey)
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
                            searchSection(grid = true)
                            if (dirs.isNotEmpty()) {
                                item(
                                    key = "hdr-dirs",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_directories),
                                        onClick = { toggleSection(BrowseFolderSection.Directories) },
                                    )
                                }
                                if (BrowseFolderSection.Directories !in collapsedSections) {
                                    items(dirs, key = { "d-${it.path}|${it.relativeName}" }) { dir ->
                                        BrowseDirectoryGridItem(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = dir.name,
                                            onClick = { enterDir(dir) },
                                            onLongClick = { toggleDirFavorite(dir) },
                                            showFavoriteStar = isDirFavorite(dir),
                                            cover = dir.coverPath?.let { BrowseCover.Local(it) },
                                            showFolderThumb = browseFolderThumbs,
                                            overflow = dirOverflow(dir),
                                        )
                                    }
                                }
                            }
                            if (galleries.isNotEmpty()) {
                                item(
                                    key = "hdr-gal",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_galleries),
                                        onClick = { toggleSection(BrowseFolderSection.Galleries) },
                                    )
                                }
                                if (BrowseFolderSection.Galleries !in collapsedSections) {
                                    items(
                                        galleries,
                                        key = { entry ->
                                            when (entry) {
                                                is BrowseEntry.FolderGallery -> "g-${entry.path}|${entry.relativeName}"
                                                is BrowseEntry.ArchiveGallery -> "a-${entry.path}"
                                                else -> "x-${entry.name}"
                                            }
                                        },
                                    ) { entry ->
                                        when (entry) {
                                            is BrowseEntry.FolderGallery -> BrowseFolderGalleryGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = entry.name,
                                                pageCount = entry.pageCount,
                                                pageCountCapped = entry.pageCountCapped,
                                                cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                                progressGid = folderEntryProgressGid(entry),
                                                showPages = showGalleryPages,
                                                onClick = { openFolderGalleryPrimary(entry) },
                                                onLongClick = { openFolderGallerySecondary(entry) },
                                                overflow = folderGalleryOverflow(entry),
                                            )
                                            is BrowseEntry.ArchiveGallery -> BrowseArchiveGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = entry.name,
                                                cover = BrowseCover.LocalArchive(entry.path),
                                                onClick = { openArchive(entry) },
                                                onLongClick = { openArchiveSecondary(entry) },
                                                pageCount = entry.pageCount,
                                                showPages = showGalleryPages,
                                                overflow = archiveOverflow(entry),
                                            )
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            if (videos.isNotEmpty()) {
                                item(
                                    key = "hdr-vid",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_videos),
                                        onClick = { toggleSection(BrowseFolderSection.Videos) },
                                    )
                                }
                                if (BrowseFolderSection.Videos !in collapsedSections) {
                                    items(videos, key = { "v-${it.path}" }) { video ->
                                        BrowseVideoGridItem(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = video.name,
                                            thumbnailSource = VideoThumbnailSource.Local(
                                                path = video.path.toString(),
                                                knownSizeBytes = video.size,
                                            ),
                                            onClick = { openVideoPrimary(video.path) },
                                            onLongClick = { openVideoSecondary(video.path) },
                                            overflow = videoOverflow(video.path, virtual = video.virtual),
                                        )
                                    }
                                }
                            }
                            if (files.isNotEmpty()) {
                                item(
                                    key = "hdr-files",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_files),
                                        onClick = { toggleSection(BrowseFolderSection.Files) },
                                    )
                                }
                                if (BrowseFolderSection.Files !in collapsedSections) {
                                    items(files, key = { "f-${it.path}" }) { file ->
                                        val isImage = isImageFileName(file.name)
                                        if (isImage) {
                                            BrowsePhotoGridImageItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = file.name,
                                                cover = BrowseCover.Local(file.path),
                                                showPhotoThumb = true,
                                                onClick = { openFolderImage(file) },
                                                onLongClick = { openExternalFile(file.path) },
                                                overflow = fileOverflow(file.path),
                                            )
                                        } else {
                                            BrowseFileGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = file.name,
                                                onClick = { openExternalFile(file.path) },
                                                onLongClick = { openExternalFile(file.path) },
                                                overflow = fileOverflow(file.path),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val listState = rememberBrowseGridState(pathKey, scrollLayoutKey)
                        FastScrollLazyVerticalGrid(
                            columns = GalleryGridDefaults.listColumns(),
                            state = listState,
                            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).fillMaxSize(),
                        ) {
                            searchSection(grid = false)
                            if (dirs.isNotEmpty()) {
                                item(
                                    key = "hdr-dirs",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_directories),
                                        onClick = { toggleSection(BrowseFolderSection.Directories) },
                                    )
                                }
                                if (BrowseFolderSection.Directories !in collapsedSections) {
                                    items(dirs, key = { "d-${it.path}|${it.relativeName}" }) { dir ->
                                        BrowseDirectoryRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = dir.name,
                                            onClick = { enterDir(dir) },
                                            onLongClick = { toggleDirFavorite(dir) },
                                            cover = dir.coverPath?.let { BrowseCover.Local(it) },
                                            showFolderThumb = browseFolderThumbs,
                                            lastModifiedMs = dir.lastModifiedMs,
                                            sizeBytes = dir.size,
                                            typeLabel = browseZipAsDirTypeLabel(dir.relativeName, dir.name) ?: "Dir",
                                            overflow = dirOverflow(dir),
                                            showFavoriteStar = isDirFavorite(dir),
                                        )
                                    }
                                }
                            }
                            if (galleries.isNotEmpty()) {
                                item(
                                    key = "hdr-gal",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_galleries),
                                        onClick = { toggleSection(BrowseFolderSection.Galleries) },
                                    )
                                }
                                if (BrowseFolderSection.Galleries !in collapsedSections) {
                                    items(
                                        galleries,
                                        key = { entry ->
                                            when (entry) {
                                                is BrowseEntry.FolderGallery -> "g-${entry.path}|${entry.relativeName}"
                                                is BrowseEntry.ArchiveGallery -> "a-${entry.path}"
                                                else -> "x-${entry.name}"
                                            }
                                        },
                                    ) { entry ->
                                        when (entry) {
                                            is BrowseEntry.FolderGallery -> BrowseFolderGalleryRow(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = entry.name,
                                                pageCount = entry.pageCount,
                                                pageCountCapped = entry.pageCountCapped,
                                                cover = entry.coverPath?.let { BrowseCover.Local(it) },
                                                progressGid = folderEntryProgressGid(entry),
                                                showPages = showGalleryPages,
                                                onClick = { openFolderGalleryPrimary(entry) },
                                                onLongClick = { openFolderGallerySecondary(entry) },
                                                lastModifiedMs = entry.lastModifiedMs,
                                                sizeBytes = entry.size,
                                                typeLabel = browseZipAsDirTypeLabel(entry.relativeName, entry.name) ?: "Folder",
                                                overflow = folderGalleryOverflow(entry),
                                            )
                                            is BrowseEntry.ArchiveGallery -> BrowseArchiveGalleryRow(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = entry.name,
                                                cover = BrowseCover.LocalArchive(entry.path),
                                                onClick = { openArchive(entry) },
                                                onLongClick = { openArchiveSecondary(entry) },
                                                fileName = entry.path.name,
                                                sizeBytes = entry.size,
                                                lastModifiedMs = entry.lastModifiedMs,
                                                pageCount = entry.pageCount,
                                                showPages = showGalleryPages,
                                                overflow = archiveOverflow(entry),
                                            )
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            if (videos.isNotEmpty()) {
                                item(
                                    key = "hdr-vid",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_videos),
                                        onClick = { toggleSection(BrowseFolderSection.Videos) },
                                    )
                                }
                                if (BrowseFolderSection.Videos !in collapsedSections) {
                                    items(videos, key = { "v-${it.path}" }) { video ->
                                        BrowseVideoRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = video.name,
                                            thumbnailSource = VideoThumbnailSource.Local(
                                                path = video.path.toString(),
                                                knownSizeBytes = video.size,
                                            ),
                                            onClick = { openVideoPrimary(video.path) },
                                            onLongClick = { openVideoSecondary(video.path) },
                                            fileName = video.path.name,
                                            sizeBytes = video.size,
                                            lastModifiedMs = video.lastModifiedMs,
                                            overflow = videoOverflow(video.path, virtual = video.virtual),
                                        )
                                    }
                                }
                            }
                            if (files.isNotEmpty()) {
                                item(
                                    key = "hdr-files",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_files),
                                        onClick = { toggleSection(BrowseFolderSection.Files) },
                                    )
                                }
                                if (BrowseFolderSection.Files !in collapsedSections) {
                                    items(files, key = { "f-${it.path}" }) { file ->
                                        val isImage = isImageFileName(file.name)
                                        BrowseFileRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = file.name,
                                            cover = if (isImage) BrowseCover.Local(file.path) else null,
                                            showPhotoThumb = isImage,
                                            onClick = {
                                                if (isImage) {
                                                    openFolderImage(file)
                                                } else {
                                                    openExternalFile(file.path)
                                                }
                                            },
                                            onLongClick = { openExternalFile(file.path) },
                                            fileName = file.path.name,
                                            sizeBytes = file.size,
                                            lastModifiedMs = file.lastModifiedMs,
                                            overflow = fileOverflow(file.path),
                                        )
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

/**
 * Photo-grid scroll: jump to reader reading progress (same [progressGid] as the folder reader)
 * when the grid opens / resumes. Falls back to saved scroll only when progress is 0.
 */
@Composable
internal fun rememberLocalPhotoGridState(
    pathKey: String,
    listMode: Int,
    progressGid: Long,
    imageCount: Int,
): LazyGridState {
    val state = remember(pathKey, listMode) { LazyGridState(0, 0) }
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
    PhotoGridScrollToProgressEffect(
        gridState = state,
        imageCount = imageCount,
        progressGid = progressGid,
        layoutKey = pathKey to listMode,
        loadSaved = { BrowseSession.localScroll(pathKey, listMode) },
    )
    return state
}

/**
 * Apply [EhDB] page progress to a photo-grid after items layout when
 * [Settings.photoGridScrollToProgress] is on.
 * Re-runs on [Lifecycle.Event.ON_RESUME] so return-from-reader lands on the latest page.
 * When the setting is off (or progress is 0), restores saved grid scroll on first open.
 */
@Composable
internal fun PhotoGridScrollToProgressEffect(
    gridState: LazyGridState,
    imageCount: Int,
    progressGid: Long,
    layoutKey: Any,
    loadSaved: () -> BrowseSession.ListScrollPosition? = { null },
) {
    val scrollToProgress by Settings.photoGridScrollToProgress.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeEpoch by remember(layoutKey) { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner, layoutKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(layoutKey, imageCount, progressGid, resumeEpoch, scrollToProgress) {
        if (imageCount <= 0) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (scrollToProgress && progressGid != 0L) {
            val page = withIOContext { EhDB.getReadProgress(progressGid) }
            if (page > 0) {
                gridState.scrollToItem(page.coerceIn(0, imageCount - 1))
                return@LaunchedEffect
            }
        }
        if (resumeEpoch == 0) {
            val saved = loadSaved() ?: return@LaunchedEffect
            val max = (gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            gridState.scrollToItem(saved.index.coerceIn(0, max), saved.offset)
        }
    }
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

/** SMB/WebDAV photo-grid: same progress-first scroll as [rememberLocalPhotoGridState]. */
@Composable
internal fun rememberSmbPhotoGridState(
    sourceId: Long,
    relativeDir: String,
    listMode: Int,
    progressGid: Long,
    imageCount: Int,
): LazyGridState {
    val pathKey = "$sourceId|$relativeDir"
    val state = remember(pathKey, listMode) { LazyGridState(0, 0) }
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
    PhotoGridScrollToProgressEffect(
        gridState = state,
        imageCount = imageCount,
        progressGid = progressGid,
        layoutKey = pathKey to listMode,
        loadSaved = { BrowseSession.smbScroll(sourceId, relativeDir, listMode) },
    )
    return state
}
