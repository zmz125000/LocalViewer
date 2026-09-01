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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseEntry
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.BrowseFolderId
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.BrowseVirtualKind
import com.hippo.ehviewer.library.EmptyArchiveRegistry
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalFolderListing
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.VideoThumbnail
import com.hippo.ehviewer.library.VideoThumbnailSource
import com.hippo.ehviewer.library.browseScrollLayoutKey
import com.hippo.ehviewer.library.filterByContentMode
import com.hippo.ehviewer.library.filterSmallGalleries
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toBrowseSections
import com.hippo.ehviewer.ui.LocalShowNavShortcutFab
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.OpenPdfExternally
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
import com.hippo.ehviewer.ui.main.BrowsePhotoGridImageItem
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.BrowseVideoGridItem
import com.hippo.ehviewer.ui.main.BrowseVideoRow
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.rememberBrowseSectionCollapse
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.flow.first
import moe.tarsin.snackbar
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

    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    // Lazy-drop non-image archives when cover open reports 0 pages (EmptyArchiveRegistry).
    val emptyArchiveRev by EmptyArchiveRegistry.revision.collectAsState()
    val displayEntries = remember(entries, emptyArchiveRev) {
        EmptyArchiveRegistry.filterLocalEntries(entries)
    }
    val search = rememberBrowseFolderSearchState()
    val focusManager = LocalFocusManager.current
    val folderId = stack.lastOrNull()?.let { BrowseFolderId.local(it.rootId, it.relativePath) }
    val contentMode = rememberEffectiveBrowseContentMode(folderId)
    val showSmallGalleries by Settings.browseShowSmallGalleries.collectAsState()
    val smallGalleryMinPages by Settings.browseSmallGalleryMinPages.collectAsState()
    val showHiddenFiles by Settings.browseShowHiddenFiles.collectAsState()
    val showVirtualGalleries by Settings.browseShowVirtualGalleries.collectAsState()
    // Same virtual-layer rules as SMB RPC root / photo grid (not regular folder-view mode).
    val virtual = if (stack.lastOrNull()?.photoGrid == true) {
        BrowseVirtualKind.PhotoGrid
    } else {
        BrowseVirtualKind.None
    }
    val photoGrid = virtual == BrowseVirtualKind.PhotoGrid
    val photoGridMode by Settings.photoGridMode.collectAsState()
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
        val base = when (virtual) {
            BrowseVirtualKind.PhotoGrid ->
                displayEntries
                    .filterIsInstance<BrowseEntry.RegularFile>()
                    .filter { isImageFileName(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            else ->
                displayEntries
                    .filterByContentMode(contentMode, showHiddenFiles, showVirtualGalleries)
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
    // Scroll down hides the top bar; scroll up brings it back (enterAlways).
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // FAB tracks the same enterAlways state (hide when bar collapses, show when it reappears).
    val showScrollFab by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction < 0.5f }
    }

    // Per-folder search: restore when climbing back / returning from reader.
    BindBrowseFolderSearch(
        folderKey = currentPath?.let { BrowseSession.localFolderSearchKey(it) },
        search = search,
        onPathChange = { scrollBehavior.state.heightOffset = 0f },
    )

    suspend fun reload(force: Boolean = false) {
        val frame = stack.lastOrNull()
        if (frame == null) {
            entries = emptyList()
            listedPath = null
            error = null
            return
        }
        // Leave→enter folder must not wait on previous path’s stuck MMR workers.
        VideoThumbnail.onBrowseFolderChanged("local:${frame.rootId}:${frame.relativePath}")
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
                    if (stack.lastOrNull()?.path == targetPath) {
                        entries = cached
                        listedPath = targetPath
                        error = null
                        // Rows visible; keep refresh indicator until listDirectory returns
                        // (deferred deep / slim still running). Match SMB/WebDAV.
                        refreshing = true
                    }
                },
            )
            // Path changed mid-load — replacement reload owns spinner state.
            if (stack.lastOrNull()?.path != targetPath) return
            entries = result
            listedPath = targetPath
            error = null
            loading = false
            refreshing = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Path change / new reload owns loading — do not clear here (same as SMB).
            throw e
        } catch (e: Throwable) {
            if (stack.lastOrNull()?.path != targetPath) return
            error = e.message
            entries = emptyList()
            listedPath = targetPath
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(stack) { reload(force = false) }

    // Turning Hidden files on: mark listing non-current so slim quick-scan deep-scans
    // shallow-tagged `.nomedia` / dot directories.
    var prevShowHidden by remember { mutableStateOf(showHiddenFiles) }
    LaunchedEffect(showHiddenFiles, stack) {
        if (showHiddenFiles && !prevShowHidden) {
            val frame = stack.lastOrNull()
            if (frame != null) {
                val key = BrowseSession.pathKey(frame.path.toPath())
                BrowseSession.getLocalCachedListing(key)?.let { cached ->
                    BrowseSession.putLocalListing(key, cached.entries, sessionCurrent = false)
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

    fun enterDir(entry: BrowseEntry.Directory) {
        val frame = stack.lastOrNull() ?: return
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
        if (!search.handleBack { focusManager.clearFocus() }) {
            goUp()
        }
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
        LocalHistory.recordLocalBrowseFolder(
            rootId = frame.rootId,
            relativePath = frame.relativePath,
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
        val child = entry.relativeName.replace('\\', '/').trim('/')
        val parent = frame.relativePath.replace('\\', '/').trim('/')
        return when {
            child.isEmpty() -> parent
            parent.isEmpty() -> child
            else -> "$parent/$child"
        }
    }

    fun openFolderGallery(entry: BrowseEntry.FolderGallery, page: Int = -1) {
        val frame = stack.lastOrNull() ?: return
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
        navToLocalFolderReader(entry.path.toString(), info, page)
    }

    /**
     * Photo-grid virtual folder for a gallery. Pushes a photo-grid frame so back returns
     * to the parent listing (does not change global list/content mode).
     */
    fun openFolderGalleryPhotoGrid(entry: BrowseEntry.FolderGallery) {
        val frame = stack.lastOrNull() ?: return
        val rel = folderGalleryRelative(entry, frame)
        updateStack(
            stack + BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = entry.path.toString(),
                title = entry.name,
                relativePath = rel,
                preferMediaStore = frame.preferMediaStore,
                photoGrid = true,
            ),
        )
    }

    /** Primary / secondary open for folder galleries based on [Settings.photoGridMode]. */
    fun openFolderGalleryPrimary(entry: BrowseEntry.FolderGallery) {
        if (photoGridMode) openFolderGalleryPhotoGrid(entry) else openFolderGallery(entry)
    }

    fun openFolderGallerySecondary(entry: BrowseEntry.FolderGallery) {
        if (photoGridMode) openFolderGallery(entry) else openFolderGalleryPhotoGrid(entry)
    }

    /**
     * Tap an image (photo-grid virtual folder **or** Folder-mode file row) → reader at that page.
     * Same page list / cover keys as the photo-grid path.
     */
    fun openFolderImage(file: BrowseEntry.RegularFile) {
        val frame = stack.lastOrNull() ?: return
        if (!isImageFileName(file.name)) return
        val images = folderImages
        if (images.isEmpty()) return
        val page = images.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
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
        navToLocalFolderReader(frame.path, info, page)
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
        val path = entry.path.toString()
        launchIO {
            // Parent browse dir (not gated by file/gallery prefs) + file row.
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalArchive(path, title = entry.name)
        }
        navToReader(path)
    }

    /** Long-press PDF → system / third-party reader (tap still uses in-app image PDF engine). */
    fun openPdfInOtherApp(entry: BrowseEntry.ArchiveGallery) {
        if (!isPdfFileName(entry.name)) return
        val path = entry.path.toString()
        launchIO {
            // Parent dir + file row (non-dir open).
            recordCurrentBrowseFolderHistory()
            LocalHistory.recordLocalFile(path, title = entry.name)
            try {
                OpenPdfExternally.openLocal(context, path, displayName = entry.name)
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

    fun openExternalFile(path: okio.Path) {
        // Always launch with the real path basename — promoted VideoFile rows use a
        // virtual `@dir` display name without extension (wrong MIME / player title).
        val actualName = path.name
        val pathStr = path.toString()
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

    /** In-app Media3 player. */
    fun playVideo(path: okio.Path) {
        val actualName = path.name
        val pathStr = path.toString()
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
                loading && (displayEntries.isEmpty() || listedPath != currentPath) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                error != null && displayEntries.isEmpty() -> {
                    BrowseEmptyHint(error!!)
                }
                displayEntries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                filteredEntries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    // List only composes when this path's entries are ready. State is keyed by
                    // path+layout so parent/child never share one LazyList scroll index.
                    val pathKey = (listedPath ?: currentPath!!) + if (photoGrid) "#pg" else ""
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
                    if (photoGrid) {
                        // Virtual image-only grid for a folder gallery (long-press).
                        val frame = stack.lastOrNull()
                        val progressGid = frame?.let {
                            stableGalleryId(it.rootId, it.relativePath.ifEmpty { "." })
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
                            items(folderImages, key = { "pg-${it.path}" }) { file ->
                                BrowsePhotoGridImageItem(
                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                    name = file.name,
                                    cover = BrowseCover.Local(file.path),
                                    showPhotoThumb = true,
                                    onClick = { openFolderImage(file) },
                                    onLongClick = { openExternalFile(file.path) },
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
                                    items(dirs, key = { "d-${it.path}" }) { dir ->
                                        BrowseDirectoryGridItem(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = dir.name,
                                            onClick = { enterDir(dir) },
                                            onLongClick = { toggleDirFavorite(dir) },
                                            showFavoriteStar = isDirFavorite(dir),
                                            cover = dir.coverPath?.let { BrowseCover.Local(it) },
                                            showFolderThumb = browseFolderThumbs,
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
                                                is BrowseEntry.FolderGallery -> "g-${entry.path}"
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
                                                showPages = showGalleryPages,
                                                onClick = { openFolderGalleryPrimary(entry) },
                                                onLongClick = { openFolderGallerySecondary(entry) },
                                            )
                                            is BrowseEntry.ArchiveGallery -> BrowseArchiveGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = entry.name,
                                                cover = BrowseCover.LocalArchive(entry.path),
                                                onClick = { openArchive(entry) },
                                                onLongClick = { openArchiveInOtherApp(entry) },
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
                                            )
                                        } else {
                                            BrowseFileGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = file.name,
                                                onClick = { openExternalFile(file.path) },
                                                onLongClick = { openExternalFile(file.path) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val listState = rememberBrowseListState(pathKey, scrollLayoutKey)
                        FastScrollLazyColumn(
                            state = listState,
                            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).fillMaxSize(),
                        ) {
                            if (dirs.isNotEmpty()) {
                                item(key = "hdr-dirs") {
                                    BrowseSectionHeader(
                                        stringResource(R.string.browse_directories),
                                        onClick = { toggleSection(BrowseFolderSection.Directories) },
                                    )
                                }
                                if (BrowseFolderSection.Directories !in collapsedSections) {
                                    items(dirs, key = { "d-${it.path}" }) { dir ->
                                        BrowseDirectoryRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = dir.name,
                                            onClick = { enterDir(dir) },
                                            onLongClick = { toggleDirFavorite(dir) },
                                            cover = dir.coverPath?.let { BrowseCover.Local(it) },
                                            showFolderThumb = browseFolderThumbs,
                                            lastModifiedMs = dir.lastModifiedMs,
                                        )
                                    }
                                }
                            }
                            if (galleries.isNotEmpty()) {
                                item(key = "hdr-gal") {
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
                                                is BrowseEntry.FolderGallery -> "g-${entry.path}"
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
                                                showPages = showGalleryPages,
                                                onClick = { openFolderGalleryPrimary(entry) },
                                                onLongClick = { openFolderGallerySecondary(entry) },
                                                lastModifiedMs = entry.lastModifiedMs,
                                            )
                                            is BrowseEntry.ArchiveGallery -> BrowseArchiveGalleryRow(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = entry.name,
                                                cover = BrowseCover.LocalArchive(entry.path),
                                                onClick = { openArchive(entry) },
                                                onLongClick = { openArchiveInOtherApp(entry) },
                                                fileName = entry.path.name,
                                                sizeBytes = entry.size,
                                                lastModifiedMs = entry.lastModifiedMs,
                                            )
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            if (videos.isNotEmpty()) {
                                item(key = "hdr-vid") {
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
                                        )
                                    }
                                }
                            }
                            if (files.isNotEmpty()) {
                                item(key = "hdr-files") {
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
