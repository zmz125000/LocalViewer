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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.ARCHIVE_DOWNLOAD_WARN_BYTES
import com.hippo.ehviewer.library.ArchiveTooLargeException
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.BrowseFolderId
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.BrowseVirtualKind
import com.hippo.ehviewer.library.EmptyArchiveRegistry
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.library.VideoThumbnail
import com.hippo.ehviewer.library.VideoThumbnailSource
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FOLDER_TOKEN
import com.hippo.ehviewer.library.browseScrollLayoutKey
import com.hippo.ehviewer.library.filterRemoteByContentMode
import com.hippo.ehviewer.library.filterRemoteSmallGalleries
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isStreamableArchiveFileName
import com.hippo.ehviewer.library.joinRemoteArchivePath
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toRemoteBrowseSections
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.LocalShowNavShortcutFab
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.OpenPdfExternally
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.BrowseScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.LibraryScreenDestination
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
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
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.navToWebDavFolderReader
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import moe.tarsin.snackbar
import moe.tarsin.string

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.WebDavBrowserScreen(
    sourceId: Long,
    initialRelativePath: String = "",
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    DrawerHandle(false)
    val context = LocalContext.current
    var source by remember { mutableStateOf<WebDavSourceEntity?>(null) }

    // Session-scoped path. Empty list = share root and is *not* "unset":
    // do not fall back to initialRelativePath when session is empty, or returning from
    // the reader after climbing to root re-opens the History deep folder.
    var segments by remember {
        val stored = BrowseSession.webDavSegmentsOrNull(sourceId)
        val initial = stored ?: initialRelativePath.split('/').filter { it.isNotEmpty() }.also {
            BrowseSession.setWebDavSegments(sourceId, it)
        }
        mutableStateOf(initial)
    }

    /**
     * How many path segments each [enterDir] appended. Promoted video leaves append
     * `S/leaf` (2); goUp pops that many so one back action returns to the listing
     * that showed the `@` row. Deep-links leave this empty → goUp drops 1.
     */
    var enterHopStack by remember { mutableStateOf(emptyList<Int>()) }

    fun updateSegments(new: List<String>) {
        segments = new
        BrowseSession.setWebDavSegments(sourceId, new)
        if (new.isEmpty()) enterHopStack = emptyList()
    }

    var entries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }

    /** Relative dir the current [entries] belong to. */
    var listedDir by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listMode by Settings.listMode.collectAsState()
    var photoGridOverlay by remember {
        mutableStateOf(BrowseSession.webDavPhotoGrid(sourceId))
    }
    fun setPhotoGrid(
        dir: String?,
        enteredFromParent: Boolean = false,
        exitToOrigin: Boolean = false,
    ) {
        photoGridOverlay = if (dir == null) {
            null
        } else {
            BrowseSession.PhotoGridOverlay(dir, enteredFromParent, exitToOrigin)
        }
        BrowseSession.setWebDavPhotoGrid(sourceId, dir, enteredFromParent, exitToOrigin)
    }
    val photoGridDir = photoGridOverlay?.dir
    val showGalleryPages by Settings.showGalleryPages.collectAsState()
    val browseFolderThumbs by Settings.browseFolderThumbs.collectAsState()
    val photoGridMode by Settings.photoGridMode.collectAsState()
    val relativeDirForMode = segments.joinToString("/")
    val virtual = if (photoGridDir == relativeDirForMode) {
        BrowseVirtualKind.PhotoGrid
    } else {
        BrowseVirtualKind.None
    }
    val photoGrid = virtual == BrowseVirtualKind.PhotoGrid
    val folderId = BrowseFolderId.webDav(sourceId, relativeDirForMode)
    val contentMode = rememberEffectiveBrowseContentMode(folderId)
    val useGrid = virtual.forceGrid || listMode == 1
    val scrollLayoutKey = browseScrollLayoutKey(listMode, contentMode, virtual)
    val favoriteKeys by Settings.favoriteBrowseSources.collectAsState()
    val addedToFavourites = stringResource(id = R.string.add_to_favourites)
    val removedFromFavourites = stringResource(id = R.string.remove_from_favourites)
    // Scroll down hides the top bar; scroll up brings it back (enterAlways).
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // FAB tracks the same enterAlways state (hide when bar collapses, show when it reappears).
    val showScrollFab by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction < 0.5f }
    }

    val relativeDir = relativeDirForMode
    val title = segments.lastOrNull() ?: source?.displayName ?: stringResource(R.string.network)

    fun dirRelative(name: String): String = if (relativeDir.isEmpty()) name else WebDavGateway.joinRelative(relativeDir, name)

    fun toggleDirFavorite(name: String, coverFileName: String? = null) {
        val rel = dirRelative(name)
        val coverKey = coverFileName?.let { fileName ->
            val coverRemote = if (rel.isEmpty()) {
                fileName
            } else {
                WebDavGateway.joinRelative(rel, fileName)
            }
            HistoryThumbKey.webdav(sourceId, coverRemote)
        }
        BrowseFavorites.toggleWebDavFolder(sourceId, rel, thumbKey = coverKey)
    }

    fun isDirFavorite(name: String): Boolean = BrowseFavorites.webDavFolderKey(sourceId, dirRelative(name)) in favoriteKeys

    val emptyArchiveRev by EmptyArchiveRegistry.revision.collectAsState()
    val displayEntries = remember(entries, emptyArchiveRev, relativeDir, sourceId) {
        EmptyArchiveRegistry.filterRemoteEntries(entries) { arch ->
            "webdav:$sourceId:${joinRemoteArchivePath(relativeDir, arch.parentRelativeName, arch.fileName)}"
        }
    }
    val search = rememberBrowseFolderSearchState()
    val focusManager = LocalFocusManager.current
    val showSmallGalleries by Settings.browseShowSmallGalleries.collectAsState()
    val smallGalleryMinPages by Settings.browseSmallGalleryMinPages.collectAsState()
    val showHiddenFiles by Settings.browseShowHiddenFiles.collectAsState()
    val showVirtualGalleries by Settings.browseShowVirtualGalleries.collectAsState()
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
                    .filterIsInstance<BrowseEntryRemote.RegularFile>()
                    .filter { isImageFileName(it.fileName.substringAfterLast('/')) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            else ->
                displayEntries
                    .filterRemoteByContentMode(contentMode, showHiddenFiles, showVirtualGalleries)
                    .filterRemoteSmallGalleries(showSmallGalleries, smallGalleryMinPages)
        }
        base.filterByBrowseSearch(search.keyword) { it.name }
    }

    /**
     * Image RegularFiles in the current listing — photo-grid virtual folder **and**
     * Folder-mode loose images (shared reader / cover keys).
     */
    val folderImages = remember(filteredEntries) {
        filteredEntries
            .filterIsInstance<BrowseEntryRemote.RegularFile>()
            .filter { isImageFileName(it.fileName.substringAfterLast('/')) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }
    val searchHint = stringResource(R.string.search_bar_hint, title)

    // Per-folder search: restore when climbing back / returning from reader.
    BindBrowseFolderSearch(
        folderKey = BrowseSession.webDavFolderSearchKey(sourceId, relativeDir),
        search = search,
        onPathChange = { scrollBehavior.state.heightOffset = 0f },
    )

    /** Detect share/pathPrefix/host edits while this screen stays on the back stack. */
    var lastConfigKey by remember { mutableStateOf<String?>(null) }

    /**
     * Bumped for pull-to-refresh / toolbar refresh / ON_RESUME soft refresh.
     * Path changes are driven solely by [relativeDir] in [LaunchedEffect] — no parallel
     * `launch { reload() }` that can race and leave [loading] stuck true.
     */
    var refreshToken by remember { mutableStateOf(0) }
    var forceNextLoad by remember { mutableStateOf(false) }

    /**
     * True after a successful full/slim list of the currently shown directory in this process.
     * Disk-hydrated (old) listings stay false so UI withholds network thumbs.
     */
    var listingSessionCurrent by remember { mutableStateOf(false) }

    /**
     * Paint session-cache listing immediately when changing path (go up / enter).
     * History → deep folder often has parent listings cached from the original browse;
     * applying them here avoids empty+spinner while the path-keyed effect starts.
     */
    fun applyCachedListing(dir: String): Boolean {
        val cached = BrowseSession.getWebDavCachedListing(sourceId, dir) ?: return false
        entries = cached.entries
        listingSessionCurrent = cached.sessionCurrent
        listedDir = dir
        loading = false
        error = null
        return true
    }

    /** RAM miss → disk index hydrate so go-up / relaunch paints before network. */
    suspend fun hydrateDiskListing(dir: String): Boolean {
        if (BrowseSession.getWebDavCachedListing(sourceId, dir) != null) {
            return applyCachedListing(dir)
        }
        val src = source ?: withIOContext { WebDavRepository.load(sourceId) }?.also { source = it }
            ?: return false
        val disk = NetworkFolderIndexCache.loadWebDav(
            sourceId,
            WebDavGateway.sourceConfigKey(src),
            dir,
        ) ?: return false
        BrowseSession.putWebDavListing(sourceId, dir, disk, sessionCurrent = false)
        entries = disk
        listingSessionCurrent = false
        listedDir = dir
        loading = false
        error = null
        return true
    }

    fun requestForceReload() {
        forceNextLoad = true
        refreshToken++
    }

    // Turning Hidden files on: mark listing non-current so slim quick-scan deep-scans
    // shallow-tagged `.nomedia` / dot directories (parity with FolderBrowserScreen).
    var prevShowHidden by remember { mutableStateOf(showHiddenFiles) }
    LaunchedEffect(showHiddenFiles, sourceId, relativeDir) {
        if (showHiddenFiles && !prevShowHidden) {
            BrowseSession.getWebDavCachedListing(sourceId, relativeDir)?.let { cached ->
                BrowseSession.putWebDavListing(
                    sourceId,
                    relativeDir,
                    cached.entries,
                    sessionCurrent = false,
                )
            }
            refreshToken++
        }
        prevShowHidden = showHiddenFiles
    }

    // Single loader for the current path. When [relativeDir] changes, Compose cancels this
    // effect and starts a new one — that is the only concurrency control we need.
    // Previous epoch/ON_RESUME races could ++epoch, early-return without clearing loading,
    // and leave History→up→up stuck on an empty infinite spinner (manual refresh worked).
    LaunchedEffect(sourceId, relativeDir, refreshToken) {
        VideoThumbnail.onBrowseFolderChanged("dav:$sourceId:$relativeDir")
        val targetDir = relativeDir
        val force = forceNextLoad
        forceNextLoad = false

        val src = withIOContext { WebDavRepository.load(sourceId) }?.also { source = it } ?: run {
            error = "Source missing"
            entries = emptyList()
            listedDir = targetDir
            loading = false
            return@LaunchedEffect
        }
        val configKey = WebDavGateway.sourceConfigKey(src)
        val configChanged = lastConfigKey != null && lastConfigKey != configKey
        lastConfigKey = configKey
        if (configChanged) {
            // Path/share changed: drop stack (session already cleared by disconnect).
            if (segments.isNotEmpty()) {
                updateSegments(emptyList())
            }
            entries = emptyList()
            listedDir = null
            // relativeDir will change → this effect is cancelled and restarted at root.
            if (targetDir.isNotEmpty()) {
                loading = false
                refreshing = false
                return@LaunchedEffect
            }
        }

        val loadDir = if (configChanged) "" else targetDir
        val haveListing = listedDir == loadDir && entries.isNotEmpty()
        // Soft resume (same path, already shown): no full-screen spinner.
        val needSpinner = force || configChanged || !haveListing
        if (needSpinner) {
            loading = true
            if (listedDir != loadDir) {
                // Prefer instant RAM/disk paint before network (especially go-up from History).
                when {
                    !force && !configChanged && applyCachedListing(loadDir) -> loading = false
                    !force && !configChanged && hydrateDiskListing(loadDir) -> loading = false
                    else -> entries = emptyList()
                }
            }
        }
        error = null

        // Password decrypt uses Android Keystore — keep it off Main (StrictMode).
        val password = withIOContext { WebDavPasswordStore.get(src.id) }
        // On cancel (path change / new refreshToken), do NOT clear loading — goUp/enterDir or
        // the replacement effect already owns that flag. Clearing here caused empty+spinner
        // races and could leave a superseded load stuck spinning forever.
        try {
            // Process-scoped list job inside gateway; effect cancel only drops this await.
            val result = WebDavGateway.listDirectory(
                src,
                password,
                loadDir,
                useCache = !force && !configChanged,
                onCached = { cached ->
                    entries = cached
                    listedDir = loadDir
                    listingSessionCurrent =
                        BrowseSession.isWebDavListingSessionCurrent(sourceId, loadDir)
                    error = null
                    loading = false
                    refreshing = false
                },
            )
            // Still the active effect for this path (not cancelled) → safe to commit.
            entries = result
            listedDir = loadDir
            listingSessionCurrent =
                BrowseSession.isWebDavListingSessionCurrent(sourceId, loadDir)
            WebDavRepository.markOk(src.id)
            error = null
            loading = false
            refreshing = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Path changed or refreshToken bumped — new effect owns loading state.
            throw e
        } catch (e: Throwable) {
            if (entries.isEmpty()) {
                error = e.message
                listedDir = loadDir
                listingSessionCurrent = false
                WebDavRepository.markError(src.id, e.message ?: "error")
            } else {
                error = null
                listingSessionCurrent =
                    BrowseSession.isWebDavListingSessionCurrent(sourceId, loadDir)
            }
            loading = false
            refreshing = false
        }
    }

    // Resume after Manage-sources edit or app background: soft refresh current path only.
    // Must not call a free-floating reload that races path changes (see LaunchedEffect above).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sourceId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Soft: keep rows if listed; token bump re-runs effect for current relativeDir.
                refreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Enter a directory by real relative path under the current listing.
     * [relativeName] may be multi-segment for promoted video leaves (`S/leaf`) —
     * never use display names like `@S-leaf` as path segments.
     */
    fun enterDir(relativeName: String) {
        val parts = relativeName.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        setPhotoGrid(null)
        // Deeper navigation owns the stack; do not jump back to History/Library on goUp.
        BrowseSession.setWebDavExitToOrigin(sourceId, false)
        val next = segments + parts
        val nextDir = next.joinToString("/")
        enterHopStack = enterHopStack + parts.size
        updateSegments(next)
        if (!applyCachedListing(nextDir)) {
            // Show spinner for uncached child; effect will load.
            entries = emptyList()
            listedDir = null
            loading = true
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

    fun goUp() {
        // Exit photo-grid: parent listing, leave browser (alwaysExitToDir off from
        // History/Library), or clear virtual layer only.
        if (photoGrid) {
            val leaveChild = photoGridOverlay?.enteredFromParent == true
            val exitToOrigin = photoGridOverlay?.exitToOrigin == true
            setPhotoGrid(null)
            when {
                leaveChild -> Unit // Fall through to pop the gallery directory.
                exitToOrigin -> {
                    navigator.popBackStack()
                    return
                }
                else -> return
            }
        }
        // Dir pin from History/Library/Fav with alwaysExitToDir off: leave immediately.
        if (BrowseSession.webDavExitToOrigin(sourceId)) {
            BrowseSession.setWebDavExitToOrigin(sourceId, false)
            jumpBackToOrigin()
            return
        }
        if (segments.isNotEmpty()) {
            val hop = (enterHopStack.lastOrNull() ?: 1).coerceIn(1, segments.size)
            enterHopStack = if (enterHopStack.isNotEmpty()) enterHopStack.dropLast(1) else enterHopStack
            val next = segments.dropLast(hop)
            val nextDir = next.joinToString("/")
            updateSegments(next)
            // History deep-link parents are often already in session cache — paint now so
            // the second/third go-up never flashes empty+infinite refresh while effect starts.
            if (!applyCachedListing(nextDir)) {
                entries = emptyList()
                listedDir = null
                loading = true
            }
        } else {
            navigator.popBackStack()
        }
    }

    val hideBackToFab by Settings.hideBackToFab.collectAsState()
    fun onTopBarBack() {
        if (hideBackToFab) jumpBackToOrigin() else goUp()
    }

    BackHandler(enabled = search.active || segments.isNotEmpty() || photoGrid) {
        if (!search.handleBack { focusManager.clearFocus() }) {
            goUp()
        }
    }

    /** History path link for the folder currently listed (parent of the opened file). */
    suspend fun recordCurrentBrowseFolderHistory(sourceId: Long) {
        val folderThumb = LocalHistory.webDavBrowseFolderThumbKey(
            sourceId = sourceId,
            relativeDir = relativeDir,
            entries = entries,
        )
        LocalHistory.recordWebDavBrowseFolder(
            sourceId = sourceId,
            relativePath = relativeDir,
            title = title,
            thumbKey = folderThumb,
        )
    }

    fun openFolderGallery(entry: BrowseEntryRemote.FolderGallery) {
        val src = source ?: return
        ReaderGalleryPlaylist.setFromWebDavBrowse(src.id, relativeDir, entries)
        val remote = if (entry.relativeName.isEmpty()) {
            relativeDir
        } else {
            WebDavGateway.joinRelative(relativeDir, entry.relativeName)
        }
        // Same remote cover path as browse [coverFor] → HistoryThumbKey → webdav_thumb_cache.
        val coverKey = entry.coverFileName?.let { fileName ->
            val coverRemote = if (entry.relativeName.isEmpty()) {
                WebDavGateway.joinRelative(relativeDir, fileName)
            } else {
                WebDavGateway.joinRelative(
                    WebDavGateway.joinRelative(relativeDir, entry.relativeName),
                    fileName,
                )
            }
            HistoryThumbKey.webdav(src.id, coverRemote)
        }
        val gid = stableGalleryId(src.id, "webdav:$remote")
        val info = BaseGalleryInfo(
            gid = gid,
            // Keep History identity on the reader info so progress FK inserts cannot
            // orphan network galleries (token=local + empty uploader).
            token = WEBDAV_FOLDER_TOKEN,
            title = entry.name,
            pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = coverKey,
            uploader = "${src.id}\u0000${remote.trim('/')}",
            category = 3,
        )
        launchIO {
            // Parent browse dir (not gated by file/gallery prefs) + gallery row.
            recordCurrentBrowseFolderHistory(src.id)
            // History = folder gallery (open → reader). Same gid as progress.
            LocalHistory.recordWebDavFolderGallery(
                sourceId = src.id,
                remoteDir = remote,
                title = entry.name,
                thumbKey = coverKey,
                pages = if (entry.pageCountCapped) 0 else entry.pageCount,
                info = info,
            )
        }
        // When capped or partial, pass empty names so reader re-lists full set
        val names = if (entry.pageCountCapped) emptyList() else entry.imageFileNames
        navToWebDavFolderReader(src.id, remote, names, info)
    }

    fun openFolderGalleryPhotoGrid(entry: BrowseEntryRemote.FolderGallery) {
        val remote = if (entry.relativeName.isEmpty()) {
            relativeDir
        } else {
            WebDavGateway.joinRelative(relativeDir, entry.relativeName)
        }
        val entered = entry.relativeName.isNotEmpty()
        if (entered) {
            enterDir(entry.relativeName)
        }
        setPhotoGrid(remote, enteredFromParent = entered)
    }

    fun openFolderGalleryPrimary(entry: BrowseEntryRemote.FolderGallery) {
        if (photoGridMode) openFolderGalleryPhotoGrid(entry) else openFolderGallery(entry)
    }

    fun openFolderGallerySecondary(entry: BrowseEntryRemote.FolderGallery) {
        if (photoGridMode) openFolderGallery(entry) else openFolderGalleryPhotoGrid(entry)
    }

    /**
     * Tap an image (photo-grid virtual folder **or** Folder-mode file row) → reader at that page.
     * Same page list / [HistoryThumbKey] cover path as the photo-grid path.
     */
    fun openFolderImage(file: BrowseEntryRemote.RegularFile) {
        val src = source ?: return
        if (!isImageFileName(file.fileName.substringAfterLast('/'))) return
        val images = folderImages
        if (images.isEmpty()) return
        val page = images.indexOfFirst { it.fileName == file.fileName }.coerceAtLeast(0)
        val names = images.map { it.fileName }
        val coverKey = names.firstOrNull()?.let { fileName ->
            HistoryThumbKey.webdav(src.id, WebDavGateway.joinRelative(relativeDir, fileName))
        }
        val gid = stableGalleryId(src.id, "webdav:$relativeDir")
        val info = BaseGalleryInfo(
            gid = gid,
            token = WEBDAV_FOLDER_TOKEN,
            title = title,
            pages = names.size,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = coverKey,
            uploader = "${src.id}\u0000${relativeDir.trim('/')}",
            category = 3,
        )
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordWebDavFolderGallery(
                sourceId = src.id,
                remoteDir = relativeDir,
                title = title,
                thumbKey = coverKey,
                pages = names.size,
                info = info,
            )
        }
        ReaderGalleryPlaylist.setFromWebDavBrowse(src.id, relativeDir, entries)
        navToWebDavFolderReader(src.id, relativeDir, names, info, page)
    }

    fun imageCoverFor(file: BrowseEntryRemote.RegularFile): BrowseCover.WebDav {
        val remote = if (relativeDir.isEmpty()) {
            file.fileName
        } else {
            WebDavGateway.joinRelative(relativeDir, file.fileName)
        }
        return BrowseCover.WebDav(sourceId, remote)
    }

    fun openPdfInOtherApp(entry: BrowseEntryRemote.ArchiveGallery) {
        if (!isPdfFileName(entry.fileName)) return
        val src = source ?: return
        val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
        launchIO {
            // Parent dir + file row (non-dir open).
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordWebDavFile(src.id, remote, title = entry.name)
            try {
                OpenPdfExternally.openWebDav(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = entry.name,
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

    /**
     * Long-press archive → system "Open with". Tap still opens in-app reader.
     * PDF uses [openPdfInOtherApp].
     */
    fun openArchiveInOtherApp(entry: BrowseEntryRemote.ArchiveGallery) {
        if (isPdfFileName(entry.fileName)) {
            openPdfInOtherApp(entry)
            return
        }
        val src = source ?: return
        val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordWebDavFile(src.id, remote, title = entry.name)
            try {
                OpenFileExternally.openWebDav(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = entry.name,
                    mimeType = mimeTypeForFileName(entry.name),
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) +
                        " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun openExternalFile(fileName: String) {
        val src = source ?: return
        // fileName may be multi-segment for promoted single-video rows (`S/leaf/movie.mp4`).
        // Launch with the real basename so MIME and player title stay correct.
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else WebDavGateway.joinRelative(relativeDir, fileName)
        launchIO {
            // Parent dir + file/video row (non-dir open).
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordWebDavFile(src.id, remote, title = actualName)
            try {
                OpenFileExternally.openWebDav(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    /** In-app Media3 player. */
    fun playVideo(fileName: String) {
        val src = source ?: return
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else WebDavGateway.joinRelative(relativeDir, fileName)
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordWebDavFile(src.id, remote, title = actualName)
            try {
                OpenFileExternally.playWebDav(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    playlistRemoteFiles = entries
                        .filterIsInstance<BrowseEntryRemote.VideoFile>()
                        .map { WebDavGateway.joinRelative(relativeDir, it.fileName) },
                )
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    /** Primary action: Media3 when [Settings.useMedia3Player] is on, else external. */
    fun openVideoPrimary(fileName: String) {
        if (Settings.useMedia3Player.value) playVideo(fileName) else openExternalFile(fileName)
    }

    /** Long-press: opposite of [openVideoPrimary]. */
    fun openVideoSecondary(fileName: String) {
        if (Settings.useMedia3Player.value) openExternalFile(fileName) else playVideo(fileName)
    }

    fun openArchive(entry: BrowseEntryRemote.ArchiveGallery) {
        val src = source ?: return
        // fileName is only the basename from the current listing — join with the folder we are in.
        val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
        launchIO {
            try {
                // Parent browse dir (not gated by file/gallery prefs) + file row.
                recordCurrentBrowseFolderHistory(src.id)
                ReaderGalleryPlaylist.setFromWebDavBrowse(src.id, relativeDir, entries)
                if (isStreamableArchiveFileName(entry.fileName) ||
                    isSolidArchiveFileName(entry.fileName) ||
                    isDocumentFileName(entry.fileName)
                ) {
                    val remoteNorm = remote.trim('/')
                    val coverKey = HistoryThumbKey.webdavArchive(src.id, remoteNorm)
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(src.id, "dava:$remoteNorm"),
                        token = WEBDAV_ARCHIVE_TOKEN,
                        title = entry.name,
                        pages = 0,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = coverKey,
                        uploader = "${src.id}\u0000$remoteNorm",
                        category = 1,
                    )
                    LocalHistory.ensureGalleryForProgress(info)
                    LocalHistory.recordWebDavStreamArchive(src.id, remoteNorm, title = entry.name, info = info)
                    withUIContext {
                        navigator.navigate(
                            ReaderScreenDestination(
                                ReaderScreenArgs.WebDavStreamArchive(
                                    sourceId = src.id,
                                    remotePath = remoteNorm,
                                    info = info,
                                ),
                            ),
                        ) { launchSingleTop = true }
                    }
                    return@launchIO
                }
                val password = WebDavPasswordStore.get(src.id)
                var allowLarge = false
                while (true) {
                    try {
                        val result = RemoteArchiveOpen.ensureWebDavArchive(
                            source = src,
                            password = password,
                            remoteRelativeFile = remote,
                            allowLarge = allowLarge,
                            onWillDownload = {
                                snackbar(string(R.string.archive_downloading))
                            },
                        )
                        LocalHistory.recordLocalArchive(
                            result.path.toString(),
                            title = entry.name,
                        )
                        withUIContext {
                            navToReader(result.path.toString())
                        }
                        return@launchIO
                    } catch (e: ArchiveTooLargeException) {
                        val miB = (e.sizeBytes / (1024 * 1024)).toInt()
                        val limit = (ARCHIVE_DOWNLOAD_WARN_BYTES / (1024 * 1024)).toInt()
                        awaitConfirmationOrCancel(title = R.string.archive_large_title) {
                            Text(string(R.string.archive_large_message, miB, limit))
                        }
                        allowLarge = true
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                snackbar(string(R.string.archive_download_failed, e.message ?: e.toString()))
            }
        }
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
                            refreshing = true
                            requestForceReload()
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // Compact phones without persistent main nav: shortcut FAB.
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
                refreshing = true
                requestForceReload()
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .browseSearchClearFocusOnInteract(search),
        ) {
            when {
                loading && (entries.isEmpty() || listedDir != relativeDir) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                error != null && displayEntries.isEmpty() -> {
                    BrowseEmptyHint(string(R.string.webdav_listing_error, error!!))
                }
                displayEntries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                filteredEntries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    val dirKey = listedDir ?: relativeDir
                    val allowRemoteThumbs = listingSessionCurrent
                    val favoritesOnTop by Settings.browseFavoritesOnTop.collectAsState()
                    val browseSortModePref by Settings.browseSortMode.collectAsState()
                    val browseSortMode = BrowseSortMode.fromPref(browseSortModePref)
                    val browseSortAscending by Settings.browseSortAscending.collectAsState()
                    val sections = filteredEntries.toRemoteBrowseSections()
                    // UI-only order; listing / folderImages / open-gallery stay name-sorted.
                    val dirsRaw = sections.directories
                        .filterIsInstance<BrowseEntryRemote.Directory>()
                        .sortedForBrowseFolderUi(
                            browseSortMode,
                            browseSortAscending,
                            nameOf = { it.name },
                            dateOf = { it.lastModifiedMs },
                        )
                    val dirs = if (favoritesOnTop) {
                        val (fav, rest) = dirsRaw.partition { isDirFavorite(it.relativeName) }
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
                        .filterIsInstance<BrowseEntryRemote.VideoFile>()
                        .sortedForBrowseFolderUi(
                            browseSortMode,
                            browseSortAscending,
                            nameOf = { it.name },
                            dateOf = { it.lastModifiedMs },
                        )
                    val files = sections.files
                        .filterIsInstance<BrowseEntryRemote.RegularFile>()
                        .sortedForBrowseFolderUi(
                            browseSortMode,
                            browseSortAscending,
                            nameOf = { it.name },
                            dateOf = { it.lastModifiedMs },
                        )
                    // In-memory only; resets when dirKey changes. No prefs / no ripple on header.
                    val animateItems by Settings.animateItems.collectAsState()
                    val (collapsedSections, toggleSection) = rememberBrowseSectionCollapse(
                        BrowseSession.webDavListingKey(sourceId, dirKey),
                    )

                    // Keys must stay unique when dual-list + "this folder as gallery" share a name
                    // (e.g. parent/ff has images and a child dir also named ff → g-self vs g-child-ff).
                    fun galleryKey(it: BrowseEntryRemote): String = when (it) {
                        is BrowseEntryRemote.FolderGallery ->
                            if (it.relativeName.isEmpty()) {
                                "g-self"
                            } else {
                                "g-child-${it.relativeName}"
                            }
                        is BrowseEntryRemote.ArchiveGallery ->
                            "a-${it.parentRelativeName}/${it.fileName}"
                        else -> "x-${it.name}"
                    }
                    fun coverFor(entry: BrowseEntryRemote.FolderGallery): BrowseCover.WebDav? = entry.coverFileName?.let { fileName ->
                        val remote = if (entry.relativeName.isEmpty()) {
                            WebDavGateway.joinRelative(relativeDir, fileName)
                        } else {
                            WebDavGateway.joinRelative(
                                WebDavGateway.joinRelative(relativeDir, entry.relativeName),
                                fileName,
                            )
                        }
                        BrowseCover.WebDav(sourceId, remote)
                    }
                    fun dirCoverFor(dir: BrowseEntryRemote.Directory): BrowseCover.WebDav? = dir.coverFileName?.let { fileName ->
                        val remote = WebDavGateway.joinRelative(
                            WebDavGateway.joinRelative(relativeDir, dir.relativeName),
                            fileName,
                        )
                        BrowseCover.WebDav(sourceId, remote)
                    }
                    fun archiveCoverFor(entry: BrowseEntryRemote.ArchiveGallery): BrowseCover? {
                        // ZIP/TAR/EPUB stream + solid RAR/7z + documents.
                        if (!isStreamableArchiveFileName(entry.fileName) &&
                            !isSolidArchiveFileName(entry.fileName) &&
                            !isDocumentFileName(entry.fileName)
                        ) {
                            return null
                        }
                        // Same path identity as openArchive / reader cacheKey.
                        val remote = joinRemoteArchivePath(
                            relativeDir,
                            entry.parentRelativeName,
                            entry.fileName,
                        )
                        return BrowseCover.WebDavArchive(sourceId, remote)
                    }
                    if (photoGrid) {
                        val progressGid = stableGalleryId(sourceId, "webdav:$relativeDir")
                        val gridState = rememberSmbPhotoGridState(
                            sourceId = sourceId,
                            relativeDir = "dav|$dirKey#pg",
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
                            items(folderImages, key = { "pg-${it.fileName}" }) { file ->
                                BrowsePhotoGridImageItem(
                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                    name = file.name,
                                    cover = imageCoverFor(file),
                                    showPhotoThumb = true,
                                    allowRemoteFetch = allowRemoteThumbs,
                                    onClick = { openFolderImage(file) },
                                    onLongClick = { openExternalFile(file.fileName) },
                                )
                            }
                        }
                    } else if (useGrid) {
                        val gridState = rememberSmbBrowseGridState(sourceId, "dav|$dirKey", scrollLayoutKey)
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
                                    items(dirs, key = { "d-${it.relativeName}" }) { dir ->
                                        BrowseDirectoryGridItem(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = dir.name,
                                            onClick = { enterDir(dir.relativeName) },
                                            onLongClick = {
                                                toggleDirFavorite(dir.relativeName, dir.coverFileName)
                                            },
                                            showFavoriteStar = isDirFavorite(dir.relativeName),
                                            cover = dirCoverFor(dir),
                                            showFolderThumb = browseFolderThumbs,
                                            thumbRetryKey = refreshToken,
                                            allowRemoteFetch = allowRemoteThumbs,
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
                                    items(galleries, key = { galleryKey(it) }) { entry ->
                                        when (entry) {
                                            is BrowseEntryRemote.FolderGallery ->
                                                BrowseFolderGalleryGridItem(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    pageCount = entry.pageCount,
                                                    pageCountCapped = entry.pageCountCapped,
                                                    cover = coverFor(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    showPages = showGalleryPages,
                                                    onClick = { openFolderGalleryPrimary(entry) },
                                                    onLongClick = { openFolderGallerySecondary(entry) },
                                                )
                                            is BrowseEntryRemote.ArchiveGallery ->
                                                BrowseArchiveGridItem(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    cover = archiveCoverFor(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
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
                                    items(videos, key = { "v-${it.fileName}" }) { video ->
                                        BrowseVideoGridItem(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = video.name,
                                            thumbnailSource = VideoThumbnailSource.WebDav(
                                                sourceId = sourceId,
                                                remoteRelativeFile = joinRemoteArchivePath(relativeDir, "", video.fileName),
                                                knownSizeBytes = video.size,
                                            ),
                                            allowRemoteFetch = allowRemoteThumbs,
                                            onClick = { openVideoPrimary(video.fileName) },
                                            onLongClick = { openVideoSecondary(video.fileName) },
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
                                    items(files, key = { "f-${it.fileName}" }) { file ->
                                        val isImage = isImageFileName(file.fileName.substringAfterLast('/'))
                                        if (isImage) {
                                            BrowsePhotoGridImageItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = file.name,
                                                cover = imageCoverFor(file),
                                                showPhotoThumb = true,
                                                allowRemoteFetch = allowRemoteThumbs,
                                                onClick = { openFolderImage(file) },
                                                onLongClick = { openExternalFile(file.fileName) },
                                            )
                                        } else {
                                            BrowseFileGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = file.name,
                                                onClick = { openExternalFile(file.fileName) },
                                                onLongClick = { openExternalFile(file.fileName) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val listState = rememberSmbBrowseListState(sourceId, "dav|$dirKey", scrollLayoutKey)
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
                                    items(dirs, key = { "d-${it.relativeName}" }) { dir ->
                                        BrowseDirectoryRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = dir.name,
                                            onClick = { enterDir(dir.relativeName) },
                                            onLongClick = {
                                                toggleDirFavorite(dir.relativeName, dir.coverFileName)
                                            },
                                            cover = dirCoverFor(dir),
                                            showFolderThumb = browseFolderThumbs,
                                            thumbRetryKey = refreshToken,
                                            allowRemoteFetch = allowRemoteThumbs,
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
                                    items(galleries, key = { galleryKey(it) }) { entry ->
                                        when (entry) {
                                            is BrowseEntryRemote.FolderGallery ->
                                                BrowseFolderGalleryRow(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    pageCount = entry.pageCount,
                                                    pageCountCapped = entry.pageCountCapped,
                                                    cover = coverFor(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    showPages = showGalleryPages,
                                                    onClick = { openFolderGalleryPrimary(entry) },
                                                    onLongClick = { openFolderGallerySecondary(entry) },
                                                    lastModifiedMs = entry.lastModifiedMs,
                                                )
                                            is BrowseEntryRemote.ArchiveGallery ->
                                                BrowseArchiveGalleryRow(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    cover = archiveCoverFor(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    onClick = { openArchive(entry) },
                                                    onLongClick = { openArchiveInOtherApp(entry) },
                                                    fileName = entry.fileName,
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
                                    items(videos, key = { "v-${it.fileName}" }) { video ->
                                        BrowseVideoRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = video.name,
                                            thumbnailSource = VideoThumbnailSource.WebDav(
                                                sourceId = sourceId,
                                                remoteRelativeFile = joinRemoteArchivePath(relativeDir, "", video.fileName),
                                                knownSizeBytes = video.size,
                                            ),
                                            allowRemoteFetch = allowRemoteThumbs,
                                            onClick = { openVideoPrimary(video.fileName) },
                                            onLongClick = { openVideoSecondary(video.fileName) },
                                            fileName = video.fileName,
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
                                    items(files, key = { "f-${it.fileName}" }) { file ->
                                        val isImage = isImageFileName(file.fileName.substringAfterLast('/'))
                                        BrowseFileRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = file.name,
                                            cover = if (isImage) imageCoverFor(file) else null,
                                            showPhotoThumb = isImage,
                                            allowRemoteFetch = allowRemoteThumbs,
                                            onClick = {
                                                if (isImage) {
                                                    openFolderImage(file)
                                                } else {
                                                    openExternalFile(file.fileName)
                                                }
                                            },
                                            onLongClick = { openExternalFile(file.fileName) },
                                            fileName = file.fileName,
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
