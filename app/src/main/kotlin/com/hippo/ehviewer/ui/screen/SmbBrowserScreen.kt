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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.ehviewer.core.database.model.SmbSourceEntity
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
import com.hippo.ehviewer.library.ARCHIVE_DOWNLOAD_WARN_BYTES
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveTooLargeException
import com.hippo.ehviewer.library.BrowseContentMode
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.BrowseFolderId
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.BrowseVirtualKind
import com.hippo.ehviewer.library.EmptyArchiveRegistry
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.FolderSearch
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.ReaderGalleryPlaylist
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.SMB_FOLDER_TOKEN
import com.hippo.ehviewer.library.VideoThumbnail
import com.hippo.ehviewer.library.VideoThumbnailSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.browseScrollLayoutKey
import com.hippo.ehviewer.library.browseUseGrid
import com.hippo.ehviewer.library.filterRemoteByContentMode
import com.hippo.ehviewer.library.filterRemoteSmallGalleries
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isEbookFileName
import com.hippo.ehviewer.library.isHtmlFileName
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isPdfOrEbookFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isStreamableArchiveFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.isZipMemberTooLarge
import com.hippo.ehviewer.library.isZipPlainFolderListing
import com.hippo.ehviewer.library.joinRemoteArchivePath
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.smbBrowseVirtual
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toRemoteBrowseSections
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.smb.smbReconnectProbeDelayMs
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.LocalShowNavShortcutFab
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.OpenPdfBySettings
import com.hippo.ehviewer.ui.OpenPdfExternally
import com.hippo.ehviewer.ui.PdfReaderMode
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
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.navToSmbFolderReader
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.util.LocalNetworkPermission
import com.hippo.ehviewer.util.addTextToClipboard
import com.hippo.ehviewer.util.ensureLocalNetworkPermission
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.tarsin.snackbar
import moe.tarsin.string

private const val SMB_RECONNECT_RETRY_DELAY_MS = 3_000L

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SmbBrowserScreen(
    sourceId: Long,
    initialRelativePath: String = "",
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    DrawerHandle(false)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var source by remember { mutableStateOf<SmbSourceEntity?>(null) }

    // Session-scoped path. Empty list = share root and is *not* "unset":
    // do not fall back to initialRelativePath when session is empty, or returning from
    // the reader after climbing to root re-opens the History deep folder.
    var segments by remember {
        val stored = BrowseSession.smbSegmentsOrNull(sourceId)
        val initial = stored ?: initialRelativePath.split('/').filter { it.isNotEmpty() }.also {
            BrowseSession.setSmbSegments(sourceId, it)
        }
        mutableStateOf(initial)
    }

    /**
     * How many path segments each [enterDir] appended. Promoted video leaves append
     * `S/leaf` (2); goUp pops that many so one back action returns to the listing
     * that showed the `@` row. Deep-links leave this empty → goUp drops 1.
     */
    var enterHopStack by remember { mutableStateOf(emptyList<Int>()) }

    /**
     * Listing that owned the Search section when a dir was opened from that section.
     * Next goUp jumps here in one hop (does not walk Album → …). Overflow Open folder
     * leaves this null. Independent of [BrowseSession.smbExitToOrigin].
     */
    var searchReturnRel by remember { mutableStateOf<String?>(null) }

    fun updateSegments(new: List<String>) {
        segments = new
        BrowseSession.setSmbSegments(sourceId, new)
        if (new.isEmpty()) enterHopStack = emptyList()
    }

    var entries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }

    /** Relative dir the current [entries] belong to. */
    var listedDir by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listMode by Settings.listMode.collectAsState()

    /** Photo-grid overlay; session-backed so reader navigation restores it. */
    var photoGridOverlay by remember {
        mutableStateOf(BrowseSession.smbPhotoGrid(sourceId))
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
        BrowseSession.setSmbPhotoGrid(sourceId, dir, enteredFromParent, exitToOrigin)
    }
    val photoGridDir = photoGridOverlay?.dir
    val showGalleryPages by Settings.showGalleryPages.collectAsState()
    val browseFolderThumbs by Settings.browseFolderThumbs.collectAsState()
    val browseZipAsDir by Settings.browseZipAsDir.collectAsState()
    val photoGridMode by Settings.photoGridMode.collectAsState()
    val networkFolderIndexCacheEnabled by Settings.networkFolderIndexCache.collectAsState()
    val networkFolderIndexQuickScanEnabled by Settings.networkFolderIndexQuickScan.collectAsState()
    val smbConnectionRevision by SmbGateway.connectionRevision.collectAsState()
    val refreshEnabled = remember(source, networkFolderIndexCacheEnabled, smbConnectionRevision) {
        !networkFolderIndexCacheEnabled || source?.let {
            // Live connect host via [SmbGateway.endpointHost] (identity host on main).
            SmbGateway.isSourceConnected(it)
        } == true
    }
    var connectionProbeToken by remember { mutableStateOf(0) }
    val sourceConnectionKey = source?.let { SmbGateway.sourceConfigKey(it) }
    val relativeDirForMode = segments.joinToString("/")
    // Virtual layers (RPC share list / photo grid / mixed zip): not regular folder-view modes.
    val zipPlainFolder = isZipPlainFolderListing(
        relativeDirForMode,
        hasFolderGallery = entries.any { it is BrowseEntryRemote.FolderGallery },
        listingReady = listedDir == relativeDirForMode && entries.isNotEmpty(),
    )
    val virtual = smbBrowseVirtual(
        isServerRootSource = source?.let { SmbGateway.isServerRootSource(it) } == true,
        relativeDir = relativeDirForMode,
        photoGridDir = photoGridDir,
        zipPlainFolder = zipPlainFolder,
    )
    val photoGrid = virtual == BrowseVirtualKind.PhotoGrid
    val photoGridNow = rememberUpdatedState(photoGrid)
    // Virtual share-list key must not govern mode under real share paths.
    val smbModeSkipAncestors = remember(sourceId, source) {
        if (source?.let { SmbGateway.isServerRootSource(it) } == true) {
            setOf(BrowseFavorites.smbFolderKey(sourceId, ""))
        } else {
            emptySet()
        }
    }
    val folderId = BrowseFolderId.smb(sourceId, relativeDirForMode)
    val contentMode = rememberEffectiveBrowseContentMode(
        folder = folderId,
        skipAncestorKeys = smbModeSkipAncestors,
    )
    val useGrid = browseUseGrid(listMode, contentMode, virtual)
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

    fun dirRelative(name: String): String = if (relativeDir.isEmpty()) name else SmbGateway.joinRelativePath(relativeDir, name)

    fun toggleDirFavorite(name: String, coverFileName: String? = null) {
        val rel = dirRelative(name)
        val coverKey = coverFileName?.let { fileName ->
            val coverRemote = if (rel.isEmpty()) {
                fileName
            } else {
                SmbGateway.joinRelativePath(rel, fileName)
            }
            HistoryThumbKey.smb(sourceId, coverRemote)
        }
        BrowseFavorites.toggleSmbFolder(sourceId, rel, thumbKey = coverKey)
    }

    fun isDirFavorite(name: String): Boolean = BrowseFavorites.smbFolderKey(sourceId, dirRelative(name)) in favoriteKeys
    val emptyArchiveRev by EmptyArchiveRegistry.revision.collectAsState()
    val displayEntries = remember(entries, emptyArchiveRev, relativeDir, sourceId) {
        EmptyArchiveRegistry.filterRemoteEntries(entries) { arch ->
            "smb:$sourceId:${joinRemoteArchivePath(relativeDir, arch.parentRelativeName, arch.fileName)}"
        }
    }
    val search = rememberBrowseFolderSearchState()
    val searchFolderKey = BrowseSession.smbFolderSearchKey(sourceId, relativeDir)
    var searchHits by remember(searchFolderKey) {
        mutableStateOf(BrowseSession.peekFolderSearchHits<BrowseEntryRemote>(searchFolderKey))
    }
    var searching by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Restore before filteredEntries / list so Search-section items exist when scroll applies.
    BindBrowseFolderSearch(
        folderKey = searchFolderKey,
        search = search,
        onPathChange = { scrollBehavior.state.heightOffset = 0f },
    )
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
        val liveSearch = search.keyword.trim().isNotEmpty()
        val base = when (virtual) {
            // Image-only virtual folder.
            BrowseVirtualKind.PhotoGrid ->
                displayEntries
                    .filterIsInstance<BrowseEntryRemote.RegularFile>()
                    .filter { isImageFileName(it.fileName.substringAfterLast('/')) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            // Share names only — no content-mode filter.
            BrowseVirtualKind.RpcShareRoot -> displayEntries
            BrowseVirtualKind.ZipPlainFolder ->
                displayEntries
                    .filterRemoteByContentMode(
                        BrowseContentMode.Folder,
                        showHiddenFiles,
                        showVirtualGalleries,
                        allTypes = liveSearch,
                    )
                    .filterRemoteSmallGalleries(showSmallGalleries, smallGalleryMinPages)
            BrowseVirtualKind.VideoFolder ->
                displayEntries
                    .filterRemoteByContentMode(
                        BrowseContentMode.Video,
                        showHiddenFiles,
                        showVirtualGalleries,
                        allTypes = liveSearch,
                    )
                    .filterRemoteSmallGalleries(showSmallGalleries, smallGalleryMinPages)
            BrowseVirtualKind.None ->
                displayEntries
                    .filterRemoteByContentMode(
                        contentMode,
                        showHiddenFiles,
                        showVirtualGalleries,
                        allTypes = liveSearch,
                    )
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

    LaunchedEffect(
        searchFolderKey,
        search.submittedKeyword,
        search.submitGeneration,
        showHiddenFiles,
    ) {
        val q = search.submittedKeyword
        if (q.isEmpty()) {
            searchHits = emptyList()
            searching = false
            BrowseSession.clearFolderSearchHits(searchFolderKey)
            return@LaunchedEffect
        }
        val cached = BrowseSession.cachedFolderSearchHits<BrowseEntryRemote>(
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
            val src = source ?: withIOContext { SmbRepository.load(sourceId) }?.also { source = it }
                ?: return@LaunchedEffect
            val password = withIOContext { SmbPasswordStore.get(src.id) }
            if (!ensureLocalNetworkPermission()) return@LaunchedEffect
            searchHits = SmbGateway.searchDirectory(
                src,
                password,
                relativeDir,
                q,
                includeHidden = showHiddenFiles,
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
     * True after a successful full/slim list of the **currently shown** directory in this
     * process. Disk-hydrated (old) listings stay false so UI withholds network thumbs.
     */
    var listingSessionCurrent by remember { mutableStateOf(false) }

    /**
     * Paint session-cache listing immediately when changing path (go up / enter).
     * History → deep folder often has parent listings cached from the original browse;
     * applying them here avoids empty+spinner while the path-keyed effect starts.
     */
    fun applyCachedListing(dir: String): Boolean {
        val cached = BrowseSession.getSmbCachedListing(sourceId, dir) ?: return false
        entries = ZipAsDirListing.presentCachedListing(cached.entries)
        listingSessionCurrent = cached.sessionCurrent
        listedDir = dir
        loading = false
        error = null
        return true
    }

    /**
     * RAM miss → try disk [NetworkFolderIndexCache] so go-up / relaunch paints before
     * network (avoids empty infinite spinner when host I/O is already idle).
     */
    suspend fun hydrateDiskListing(dir: String): Boolean {
        if (BrowseSession.getSmbCachedListing(sourceId, dir) != null) {
            return applyCachedListing(dir)
        }
        val src = source ?: withIOContext { SmbRepository.load(sourceId) }?.also { source = it }
            ?: return false
        val disk = NetworkFolderIndexCache.loadSmb(
            sourceId,
            SmbGateway.sourceConfigKey(src),
            dir,
        ) ?: return false
        val presented = ZipAsDirListing.presentCachedListing(disk)
        BrowseSession.putSmbListing(sourceId, dir, presented, sessionCurrent = false)
        entries = presented
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

    var prevZipAsDir by remember { mutableStateOf(browseZipAsDir) }
    LaunchedEffect(browseZipAsDir) {
        if (browseZipAsDir != prevZipAsDir) {
            prevZipAsDir = browseZipAsDir
            if (!browseZipAsDir) {
                ZipAsDirListing.parentSegmentsOfZipBrowsePath(relativeDir)?.let { parent ->
                    updateSegments(parent)
                }
            }
            requestForceReload()
        }
    }

    /*
     * A failed foreground SMB connection used to get only one passive probe. The circuit
     * cooldown was at most 3s, but nothing scheduled the next attempt, so cached folders
     * could remain disconnected until the screen resumed. Retry sequentially (never overlap)
     * with a 5s gap between attempts. Leaving or pausing the screen cancels the loop.
     */
    LaunchedEffect(
        screenResumed,
        sourceConnectionKey,
        smbConnectionRevision,
        connectionProbeToken,
        loading,
    ) {
        if (!screenResumed || loading) return@LaunchedEffect
        val src = source ?: return@LaunchedEffect
        if (SmbGateway.isSourceConnected(src)) return@LaunchedEffect
        if (!ensureLocalNetworkPermission()) return@LaunchedEffect
        val password = withIOContext { SmbPasswordStore.get(src.id) }
        var attempt = 0
        while (!SmbGateway.isSourceConnected(src)) {
            delay(smbReconnectProbeDelayMs(attempt, SMB_RECONNECT_RETRY_DELAY_MS))
            attempt++
            SmbGateway.refreshConnectionSignal(src, password)
            if (SmbGateway.isSourceConnected(src)) return@LaunchedEffect
        }
    }

    /*
     * Consume each disconnected -> connected edge once. If this exact folder is still an
     * old disk-hydrated listing, re-enter the normal cache-hit loader so it performs its
     * quick scan and marks the folder session-current. Other connectionRevision changes
     * cannot retrigger it because sourceWasConnected stays true.
     */
    var sourceWasConnected by remember(sourceConnectionKey) {
        mutableStateOf(source?.let { SmbGateway.isSourceConnected(it) } == true)
    }
    LaunchedEffect(
        screenResumed,
        sourceConnectionKey,
        relativeDir,
        smbConnectionRevision,
        networkFolderIndexQuickScanEnabled,
        loading,
    ) {
        val src = source ?: return@LaunchedEffect
        val connected = SmbGateway.isSourceConnected(src)
        val reconnected = connected && !sourceWasConnected
        sourceWasConnected = connected
        if (!screenResumed || loading || !reconnected) return@LaunchedEffect

        val cached = BrowseSession.getSmbCachedListing(sourceId, relativeDir)
        if (networkFolderIndexQuickScanEnabled &&
            cached != null &&
            !cached.sessionCurrent
        ) {
            refreshToken++
        } else if (cached == null && entries.isEmpty() && error != null) {
            requestForceReload()
        }
    }

    // Turning Hidden files on: mark listing non-current so slim quick-scan deep-scans
    // shallow-tagged `.nomedia` / dot directories (parity with FolderBrowserScreen).
    var prevShowHidden by remember { mutableStateOf(showHiddenFiles) }
    LaunchedEffect(showHiddenFiles, sourceId, relativeDir) {
        if (showHiddenFiles && !prevShowHidden) {
            BrowseSession.getSmbCachedListing(sourceId, relativeDir)?.let { cached ->
                BrowseSession.putSmbListing(
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
        // New folder must not wait on previous folder's stuck MMR pool threads.
        VideoThumbnail.onBrowseFolderChanged("smb:$sourceId:$relativeDir")
        ArchiveCoverCache.onBrowseFolderChanged("smb:$sourceId:$relativeDir")
        val targetDir = relativeDir
        val force = forceNextLoad
        forceNextLoad = false

        val src = withIOContext { SmbRepository.load(sourceId) }?.also { source = it } ?: run {
            error = "Source missing"
            entries = emptyList()
            listedDir = targetDir
            loading = false
            return@LaunchedEffect
        }
        val configKey = SmbGateway.sourceConfigKey(src)
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
        // Photo-grid open: same complete index the reader uses — no directory scan.
        if (!force && !configChanged && photoGridNow.value) {
            val names = FolderGalleryIndex.loadSmb(src.id, configKey, loadDir)
            if (!names.isNullOrEmpty()) {
                if (listedDir != loadDir || entries.isEmpty()) {
                    entries = FolderGalleryIndex.photoGridRemoteFiles(names)
                    listedDir = loadDir
                    listingSessionCurrent = true
                }
                loading = false
                refreshing = false
                error = null
                return@LaunchedEffect
            }
        }
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
        val password = withIOContext { SmbPasswordStore.get(src.id) }
        if (!ensureLocalNetworkPermission()) {
            val denied = LocalNetworkPermission.deniedMessage(context)
            if (entries.isEmpty()) {
                error = denied
                listedDir = loadDir
                listingSessionCurrent = false
                SmbRepository.markError(src.id, denied)
            }
            loading = false
            refreshing = false
            return@LaunchedEffect
        }
        // On cancel (path change / new refreshToken), do NOT clear loading — goUp/enterDir or
        // the replacement effect already owns that flag. Clearing here caused empty+spinner
        // races and could leave a superseded load stuck spinning forever.
        try {
            // Process-scoped list job inside gateway; effect cancel only drops this await.
            val result = SmbGateway.listDirectory(
                src,
                password,
                loadDir,
                useCache = !force && !configChanged,
                onCached = { cached ->
                    entries = cached
                    listedDir = loadDir
                    listingSessionCurrent =
                        BrowseSession.isSmbListingSessionCurrent(sourceId, loadDir)
                    error = null
                    loading = false
                    refreshing = true
                },
                onRefreshDone = {
                    if (listedDir == loadDir) refreshing = false
                },
            )
            // Still the active effect for this path (not cancelled) → safe to commit.
            entries = result
            listedDir = loadDir
            listingSessionCurrent =
                BrowseSession.isSmbListingSessionCurrent(sourceId, loadDir)
            SmbRepository.markOk(src.id)
            error = null
            loading = false
            refreshing = SmbGateway.isListing(sourceId, loadDir)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Path changed or refreshToken bumped — new effect owns loading state.
            throw e
        } catch (e: Throwable) {
            // Keep painted cache on failure; only show error when there is nothing to list.
            if (entries.isEmpty()) {
                error = e.message
                listedDir = loadDir
                listingSessionCurrent = false
                SmbRepository.markError(src.id, e.message ?: "error")
            } else {
                error = null
                listingSessionCurrent =
                    BrowseSession.isSmbListingSessionCurrent(sourceId, loadDir)
            }
            loading = false
            refreshing = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            VideoThumbnail.onBrowseFolderLeft("smb:")
            ArchiveCoverCache.onBrowseFolderLeft("smb:")
        }
    }

    // Resume after Manage-sources edit or a real pool drop: soft refresh current path only.
    // Returning from the in-app reader / external player with a live pool keeps the listing.
    // Must not call a free-floating reload that races path changes (see LaunchedEffect above).
    val currentSource = rememberUpdatedState(source)
    DisposableEffect(lifecycleOwner, sourceId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    screenResumed = true
                    val src = currentSource.value
                    if (src == null || !SmbGateway.isSourceConnected(src)) {
                        refreshToken++
                    }
                    connectionProbeToken++
                }
                Lifecycle.Event.ON_PAUSE -> {
                    screenResumed = false
                    connectionProbeToken++
                }
                else -> Unit
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
    fun enterDir(relativeName: String, fromSearch: Boolean = false) {
        val parts = relativeName.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        setPhotoGrid(null)
        if (fromSearch) {
            if (searchReturnRel == null) searchReturnRel = relativeDir
        } else {
            searchReturnRel = null
            // Deeper navigation owns the stack; do not jump back to History/Library on goUp.
            BrowseSession.setSmbExitToOrigin(sourceId, false)
        }
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

    /** Overflow "Open folder". No-op when the target is already this listing. */
    fun openBrowseFolder(targetRel: String) {
        if (targetRel.isEmpty()) return
        searchReturnRel = null
        search.close()
        BrowseSession.putFolderSearch(searchFolderKey, search.snapshot())
        enterDir(targetRel)
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
        if (BrowseSession.smbExitToOrigin(sourceId) && searchReturnRel == null) {
            BrowseSession.setSmbExitToOrigin(sourceId, false)
            jumpBackToOrigin()
            return
        }
        val searchOrigin = searchReturnRel
        if (searchOrigin != null) {
            searchReturnRel = null
            val target = FolderSearch.searchReturnDir(searchOrigin, relativeDir)
            if (target != null) {
                val originSegs = target.split('/').filter { it.isNotEmpty() }
                enterHopStack = emptyList()
                updateSegments(originSegs)
                if (!applyCachedListing(target)) {
                    entries = emptyList()
                    listedDir = null
                    loading = true
                }
                return
            }
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

    BackHandler(enabled = searchReturnRel != null || search.active || segments.isNotEmpty() || photoGrid) {
        // Search-section dir enter: first back returns to search, not close-search-in-child.
        if (searchReturnRel == null && search.handleBack { focusManager.clearFocus() }) {
            return@BackHandler
        }
        goUp()
    }

    /** History path link for the folder currently listed (parent of the opened file). */
    suspend fun recordCurrentBrowseFolderHistory(sourceId: Long) {
        val folderThumb = LocalHistory.smbBrowseFolderThumbKey(
            sourceId = sourceId,
            relativeDir = relativeDir,
            entries = entries,
        )
        LocalHistory.recordSmbBrowseFolder(
            sourceId = sourceId,
            relativePath = relativeDir,
            title = title,
            thumbKey = folderThumb,
        )
    }

    fun folderEntryProgressGid(entry: BrowseEntryRemote.FolderGallery): Long {
        val src = source ?: return 0L
        val remote = if (entry.relativeName.isEmpty()) {
            relativeDir
        } else {
            SmbGateway.joinRelativePath(relativeDir, entry.relativeName)
        }
        return stableGalleryId(src.id, "smb:$remote")
    }

    fun openFolderGallery(entry: BrowseEntryRemote.FolderGallery) {
        val src = source ?: return
        ReaderGalleryPlaylist.setFromSmbBrowse(src.id, relativeDir, entries)
        val remote = if (entry.relativeName.isEmpty()) {
            relativeDir
        } else {
            SmbGateway.joinRelativePath(relativeDir, entry.relativeName)
        }
        // Same remote cover path as browse [coverFor] → HistoryThumbKey → smb_thumb_cache.
        val coverKey = LocalHistory.zipOrRemoteThumbKey(
            sourceId = src.id,
            listedDir = relativeDir,
            relativeName = entry.relativeName,
            coverFileName = entry.coverFileName,
            smb = true,
        )
        val gid = stableGalleryId(src.id, "smb:$remote")
        val info = BaseGalleryInfo(
            gid = gid,
            // Keep History identity on the reader info so progress FK inserts cannot
            // orphan network galleries (token=local + empty uploader).
            token = SMB_FOLDER_TOKEN,
            title = entry.name,
            pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = coverKey,
            uploader = "${src.id}\u0000${remote.trim('/')}",
            category = 2,
        )
        launchIO {
            // Parent browse dir (not gated by file/gallery prefs) + gallery row.
            recordCurrentBrowseFolderHistory(src.id)
            // History = folder gallery (open → reader). Same gid as progress.
            LocalHistory.recordSmbFolderGallery(
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
        navToSmbFolderReader(src.id, remote, names, info)
    }

    /**
     * Photo-grid virtual folder for a gallery. Does not change global list/content mode.
     * Back returns to the parent listing (leaves the gallery dir when open entered it).
     */
    fun openFolderGalleryPhotoGrid(entry: BrowseEntryRemote.FolderGallery) {
        val remote = if (entry.relativeName.isEmpty()) {
            relativeDir
        } else {
            SmbGateway.joinRelativePath(relativeDir, entry.relativeName)
        }
        val entered = entry.relativeName.isNotEmpty()
        val names = FolderGalleryIndex.completeNames(entry)
        val parentCurrent = listingSessionCurrent
        if (entered) {
            enterDir(entry.relativeName)
        }
        // enterDir clears photo grid; re-enable for the target path.
        setPhotoGrid(remote, enteredFromParent = entered)
        // Child gallery: paint the reader file list now so enterDir does not scan.
        // Current-dir overlay keeps the existing listing (leave photo-grid stays here).
        if (entered && names != null) {
            entries = FolderGalleryIndex.photoGridRemoteFiles(names)
            listedDir = remote
            listingSessionCurrent = parentCurrent
            loading = false
            refreshing = false
            error = null
        }
    }

    /** Primary / secondary open for folder galleries based on [Settings.photoGridMode]. */
    fun openFolderGalleryPrimary(entry: BrowseEntryRemote.FolderGallery) {
        if (photoGridMode) openFolderGalleryPhotoGrid(entry) else openFolderGallery(entry)
    }

    fun openFolderGallerySecondary(entry: BrowseEntryRemote.FolderGallery) {
        if (photoGridMode) openFolderGallery(entry) else openFolderGalleryPhotoGrid(entry)
    }

    fun openNestedFolderImage(parentRel: String, fileName: String) {
        val src = source ?: return
        val remote = if (relativeDir.isEmpty()) {
            parentRel
        } else {
            SmbGateway.joinRelativePath(relativeDir, parentRel)
        }
        launchIO {
            val password = SmbPasswordStore.get(src.id)
            val names = runCatching {
                SmbGateway.listImageFileNames(src, password, remote)
            }.getOrDefault(emptyList())
            if (names.isEmpty()) return@launchIO
            val page = names.indexOfFirst { it.equals(fileName, ignoreCase = true) }.coerceAtLeast(0)
            val coverKey = names.firstOrNull()?.let { coverName ->
                LocalHistory.zipOrRemoteThumbKey(
                    sourceId = src.id,
                    listedDir = remote,
                    relativeName = "",
                    coverFileName = coverName,
                    smb = true,
                )
            }
            val galleryTitle = FolderSearch.baseName(parentRel).ifEmpty { title }
            val gid = stableGalleryId(src.id, "smb:$remote")
            val info = BaseGalleryInfo(
                gid = gid,
                token = SMB_FOLDER_TOKEN,
                title = galleryTitle,
                pages = names.size,
                favoriteSlot = NOT_FAVORITED,
                rating = -1f,
                thumbKey = coverKey,
                uploader = "${src.id}\u0000${remote.trim('/')}",
                category = 2,
            )
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFolderGallery(
                sourceId = src.id,
                remoteDir = remote,
                title = galleryTitle,
                thumbKey = coverKey,
                pages = names.size,
                info = info,
            )
            withUIContext {
                navToSmbFolderReader(src.id, remote, names, info, page)
            }
        }
    }

    /**
     * Tap an image (photo-grid virtual folder **or** Folder-mode file row) → reader at that page.
     * Same page list / [HistoryThumbKey] cover path as the photo-grid path.
     */
    fun openFolderImage(file: BrowseEntryRemote.RegularFile) {
        val src = source ?: return
        val rel = file.fileName.replace('\\', '/').trim('/')
        val fileName = FolderSearch.baseName(rel)
        if (!isImageFileName(fileName)) return
        val parentRel = FolderSearch.parentRelative(rel)
        val inListing = parentRel.isEmpty() &&
            folderImages.any { it.fileName == file.fileName }
        if (!inListing) {
            openNestedFolderImage(parentRel, fileName)
            return
        }
        val images = folderImages
        val page = images.indexOfFirst { it.fileName == file.fileName }.coerceAtLeast(0)
        val names = images.map { it.fileName }
        val coverKey = names.firstOrNull()?.let { coverName ->
            LocalHistory.zipOrRemoteThumbKey(
                sourceId = src.id,
                listedDir = relativeDir,
                relativeName = "",
                coverFileName = coverName,
                smb = true,
            )
        }
        val gid = stableGalleryId(src.id, "smb:$relativeDir")
        val info = BaseGalleryInfo(
            gid = gid,
            token = SMB_FOLDER_TOKEN,
            title = title,
            pages = names.size,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = coverKey,
            uploader = "${src.id}\u0000${relativeDir.trim('/')}",
            category = 2,
        )
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFolderGallery(
                sourceId = src.id,
                remoteDir = relativeDir,
                title = title,
                thumbKey = coverKey,
                pages = names.size,
                info = info,
            )
        }
        ReaderGalleryPlaylist.setFromSmbBrowse(src.id, relativeDir, entries)
        navToSmbFolderReader(src.id, relativeDir, names, info, page)
    }

    fun imageCoverFor(file: BrowseEntryRemote.RegularFile): BrowseCover {
        ZipAsDirListing.zipAsDirCoverParts(relativeDir, "", file.fileName)?.let { (zipRel, member) ->
            return BrowseCover.SmbZipMember(sourceId, zipRel, member)
        }
        val remote = if (relativeDir.isEmpty()) {
            file.fileName
        } else {
            SmbGateway.joinRelativePath(relativeDir, file.fileName)
        }
        return BrowseCover.Smb(sourceId, remote)
    }

    fun openPdfInOtherApp(entry: BrowseEntryRemote.ArchiveGallery, usePreferredReader: Boolean = true) {
        if (!isPdfFileName(entry.fileName)) return
        val src = source ?: return
        val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
        launchIO {
            // Parent dir + file row (non-dir open).
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFile(src.id, remote, title = entry.name)
            try {
                OpenPdfExternally.openSmb(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = entry.name,
                    usePreferredReader = usePreferredReader,
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(
                        R.string.open_pdf_external_failed,
                        e.message ?: e.toString(),
                    ),
                )
            }
        }
    }

    fun openPdfReader(entry: BrowseEntryRemote.ArchiveGallery) {
        if (!isPdfOrEbookFileName(entry.fileName)) return
        val src = source ?: return
        ReaderGalleryPlaylist.setFromSmbBrowse(src.id, relativeDir, entries)
        val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            val remoteNorm = remote.trim('/')
            val info = BaseGalleryInfo(
                gid = stableGalleryId(src.id, "smba:$remoteNorm"),
                token = SMB_ARCHIVE_TOKEN,
                title = entry.name,
                pages = 0,
                favoriteSlot = NOT_FAVORITED,
                rating = -1f,
                thumbKey = HistoryThumbKey.smbArchive(src.id, remoteNorm),
                uploader = "${src.id}\u0000$remoteNorm",
                category = 1,
            )
            LocalHistory.ensureGalleryForProgress(info)
            LocalHistory.recordSmbStreamArchive(src.id, remoteNorm, title = entry.name, info = info)
            val page = runCatching { EhDB.getReadProgress(info.gid) }.getOrDefault(0)
            try {
                OpenPdfExternally.openInternalSmb(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = entry.name,
                    progressGid = info.gid,
                    startPage = page,
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(
                        R.string.pdf_reader_open_failed,
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
            LocalHistory.recordSmbFile(src.id, remote, title = entry.name)
            try {
                OpenFileExternally.openSmb(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = entry.name,
                    mimeType = mimeTypeForFileName(entry.name),
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(R.string.browse_open_failed) +
                        " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun openInternalDocument(fileName: String) {
        val src = source ?: return
        ReaderGalleryPlaylist.setFromSmbBrowse(src.id, relativeDir, entries)
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFile(src.id, remote, title = actualName)
            try {
                OpenFileExternally.playDocumentSmb(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(R.string.pdf_reader_open_failed, e.message ?: e.toString()),
                )
            }
        }
    }

    fun openExternalFile(fileName: String, asFile: Boolean = false, usePreferredPlayer: Boolean = true) {
        val src = source ?: return
        // fileName may be multi-segment for promoted single-video rows (`S/leaf/movie.mp4`).
        // Launch with the real basename so MIME and player title stay correct.
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            // Parent dir + file/video row (non-dir open).
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFile(src.id, remote, title = actualName)
            try {
                OpenFileExternally.openSmb(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    asFile = asFile,
                    usePreferredPlayer = usePreferredPlayer,
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun openSmbHtml(fileName: String, incognito: Boolean) {
        val src = source ?: return
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFile(src.id, remote, title = actualName)
            try {
                OpenFileExternally.openSmbHtml(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    incognito = incognito,
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun copySmbHtmlUrl(fileName: String) {
        val src = source ?: return
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            try {
                val uri = OpenFileExternally.ensureSmbHtmlHttpUri(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                )
                withUIContext {
                    with(context) { addTextToClipboard(uri.toString()) }
                }
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
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
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            recordCurrentBrowseFolderHistory(src.id)
            LocalHistory.recordSmbFile(src.id, remote, title = actualName)
            try {
                OpenFileExternally.playSmb(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                    playlistRemoteFiles = entries
                        .filterIsInstance<BrowseEntryRemote.VideoFile>()
                        .map { SmbGateway.joinRelativePath(relativeDir, it.fileName) },
                )
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
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

    fun openArchive(entry: BrowseEntryRemote.ArchiveGallery, skipPdfPrimary: Boolean = false) {
        val src = source ?: return
        if (!skipPdfPrimary && isEbookFileName(entry.fileName)) {
            openPdfReader(entry)
            return
        }
        if (!skipPdfPrimary && isPdfFileName(entry.fileName)) {
            when (Settings.pdfReaderMode.value) {
                PdfReaderMode.PDF -> {
                    openPdfReader(entry)
                    return
                }
                PdfReaderMode.EXTERNAL -> {
                    openPdfInOtherApp(entry)
                    return
                }
                else -> Unit
            }
        }
        if (browseZipAsDir && isZipArchiveFileName(entry.fileName)) {
            enterDir(entry.fileName)
            return
        }
        // fileName is only the basename from the current listing — join with the folder we are in.
        val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
        launchIO {
            try {
                // Parent browse dir (not gated by file/gallery prefs) + file row.
                recordCurrentBrowseFolderHistory(src.id)
                ReaderGalleryPlaylist.setFromSmbBrowse(src.id, relativeDir, entries)
                // Stream ZIP/CBZ/TAR/CBT/EPUB, solid RAR/CBR/7z, or document extract.
                if (isStreamableArchiveFileName(entry.fileName) ||
                    isSolidArchiveFileName(entry.fileName) ||
                    isDocumentFileName(entry.fileName)
                ) {
                    val remoteNorm = remote.trim('/')
                    val coverKey = HistoryThumbKey.smbArchive(src.id, remoteNorm)
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(src.id, "smba:$remoteNorm"),
                        token = SMB_ARCHIVE_TOKEN,
                        title = entry.name,
                        pages = 0,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = coverKey,
                        uploader = "${src.id}\u0000$remoteNorm",
                        category = 1,
                    )
                    LocalHistory.ensureGalleryForProgress(info)
                    LocalHistory.recordSmbStreamArchive(src.id, remoteNorm, title = entry.name, info = info)
                    withUIContext {
                        navigator.navigate(
                            ReaderScreenDestination(
                                ReaderScreenArgs.SmbStreamArchive(
                                    sourceId = src.id,
                                    remotePath = remoteNorm,
                                    info = info,
                                    skipPdfPrimary = skipPdfPrimary,
                                ),
                            ),
                        ) { launchSingleTop = true }
                    }
                    return@launchIO
                }
                // Other archive types: download whole archive then open as local.
                val password = SmbPasswordStore.get(src.id)
                var allowLarge = false
                while (true) {
                    try {
                        val result = RemoteArchiveOpen.ensureSmbArchive(
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
                // User cancelled large-archive confirm or left the screen.
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(string(R.string.archive_download_failed, e.message ?: e.toString()))
            }
        }
    }

    fun openPdfSecondary(entry: BrowseEntryRemote.ArchiveGallery) {
        when (Settings.pdfReaderMode.value) {
            PdfReaderMode.AUTO -> {
                val src = source ?: return
                val remote = joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName)
                OpenPdfBySettings.launchOtherBuiltin(
                    context,
                    ReaderScreenArgs.SmbStreamArchive(sourceId = src.id, remotePath = remote.trim('/')),
                )
            }
            PdfReaderMode.PDF, PdfReaderMode.EXTERNAL -> openArchive(entry, skipPdfPrimary = true)
            else -> openPdfReader(entry)
        }
    }

    fun openArchiveSecondary(entry: BrowseEntryRemote.ArchiveGallery) {
        if (isPdfOrEbookFileName(entry.fileName)) {
            openPdfSecondary(entry)
        } else {
            openArchiveInOtherApp(entry)
        }
    }

    fun notSupportedAction() {
        launch { snackbar(context.getString(R.string.browse_action_not_supported)) }
    }

    fun copySmbVideoUrl(fileName: String) {
        val src = source ?: return
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            try {
                val uri = OpenFileExternally.ensureSmbVideoHttpUri(
                    context = context,
                    sourceId = src.id,
                    remoteRelativeFile = remote,
                    displayName = actualName,
                    mimeType = mimeTypeForFileName(actualName),
                )
                withUIContext {
                    with(context) { addTextToClipboard(uri.toString()) }
                }
            } catch (e: Throwable) {
                if (e.isZipMemberTooLarge()) return@launchIO
                snackbar(
                    context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
                )
            }
        }
    }

    fun saveSmbFile(fileName: String, displayName: String = fileName.substringAfterLast('/')) {
        val src = source ?: return
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            with(context) { BrowseSaveAs.saveSmbFile(src.id, remote, displayName) }
        }
    }

    fun shareSmbFile(fileName: String, displayName: String = fileName.substringAfterLast('/')) {
        val src = source ?: return
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        launchIO {
            with(context) { BrowseSaveAs.shareSmbFile(src.id, remote, displayName) }
        }
    }

    fun saveSmbFolder(relativeName: String, displayName: String = relativeName.substringAfterLast('/')) {
        val src = source ?: return
        val remote = if (relativeDir.isEmpty()) relativeName else SmbGateway.joinRelativePath(relativeDir, relativeName)
        val name = displayName.ifEmpty { relativeName.substringAfterLast('/') }
        launchIO {
            with(context) { BrowseSaveAs.saveSmbFolder(src.id, remote, name) }
        }
    }

    fun shareSmbViaHttp(block: suspend () -> HttpShareItem) {
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

    fun smbHttpShareFile(fileName: String): (() -> Unit)? {
        val src = source ?: return null
        val actualName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val remote = if (relativeDir.isEmpty()) fileName else SmbGateway.joinRelativePath(relativeDir, fileName)
        return {
            shareSmbViaHttp {
                HttpShare.startSmbFile(
                    context,
                    src.id,
                    remote,
                    actualName,
                    mimeTypeForFileName(actualName),
                )
            }
        }
    }

    fun smbHttpShareFolder(relativeName: String, displayName: String = relativeName.substringAfterLast('/')): (() -> Unit)? {
        val src = source ?: return null
        val remote = if (relativeDir.isEmpty()) relativeName else SmbGateway.joinRelativePath(relativeDir, relativeName)
        val name = displayName.ifEmpty { relativeName.substringAfterLast('/') }
        HttpShare.zipFileRelativeForFolderShare(remote)?.let { zipRel ->
            val zipName = zipRel.substringAfterLast('/').ifEmpty { name }
            return {
                shareSmbViaHttp {
                    HttpShare.startSmbFile(
                        context,
                        src.id,
                        zipRel,
                        zipName,
                        mimeTypeForFileName(zipName),
                    )
                }
            }
        }
        return {
            shareSmbViaHttp { HttpShare.startSmbFolder(context, src.id, remote, name) }
        }
    }

    fun dirOverflow(name: String, coverFileName: String? = null, virtual: Boolean = false) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Common,
        favorited = isDirFavorite(name),
        onFavorite = { toggleDirFavorite(name, coverFileName) },
        onSaveAs = { saveSmbFolder(name) },
        onShareViaHttp = smbHttpShareFolder(name),
        onOpenFolder = {
            openBrowseFolder(FolderSearch.openFolderTarget(name, isDirectory = true, virtual = virtual))
        },
        onUnsupported = { notSupportedAction() },
    )

    fun folderGalleryOverflow(entry: BrowseEntryRemote.FolderGallery) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Gallery,
        favorited = isDirFavorite(entry.relativeName),
        onFavorite = { toggleDirFavorite(entry.relativeName, entry.coverFileName) },
        onRead = { openFolderGallery(entry) },
        onPhotoGrid = { openFolderGalleryPhotoGrid(entry) },
        onSaveAs = { saveSmbFolder(entry.relativeName, entry.name) },
        onShareViaHttp = smbHttpShareFolder(entry.relativeName, entry.name),
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

    fun archiveOverflow(entry: BrowseEntryRemote.ArchiveGallery) = if (isPdfOrEbookFileName(entry.fileName)) {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Pdf,
            onRead = { openArchive(entry, skipPdfPrimary = true) },
            onPlay = { openPdfReader(entry) },
            onExternalPlayer = {
                if (isPdfFileName(entry.fileName)) {
                    openPdfInOtherApp(entry)
                } else {
                    openArchiveInOtherApp(entry)
                }
            },
            onOpenWith = {
                if (isPdfFileName(entry.fileName)) {
                    openPdfInOtherApp(entry, usePreferredReader = false)
                } else {
                    openArchiveInOtherApp(entry)
                }
            },
            onSaveAs = {
                saveSmbFile(
                    joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
                    entry.fileName.substringAfterLast('/'),
                )
            },
            onShare = {
                shareSmbFile(
                    joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
                    entry.fileName.substringAfterLast('/'),
                )
            },
            onShareViaHttp = smbHttpShareFile(
                joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
            ),
            onOpenFolder = {
                openBrowseFolder(
                    FolderSearch.openFolderTarget(
                        joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
                        isDirectory = false,
                    ),
                )
            },
            onUnsupported = { notSupportedAction() },
        )
    } else {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Gallery,
            onRead = { openArchive(entry) },
            onOpenWith = { openArchiveInOtherApp(entry) },
            onSaveAs = {
                saveSmbFile(
                    joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
                    entry.fileName.substringAfterLast('/'),
                )
            },
            onShare = {
                shareSmbFile(
                    joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
                    entry.fileName.substringAfterLast('/'),
                )
            },
            onShareViaHttp = smbHttpShareFile(
                joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
            ),
            onOpenFolder = {
                openBrowseFolder(
                    FolderSearch.openFolderTarget(
                        joinRemoteArchivePath("", entry.parentRelativeName, entry.fileName),
                        isDirectory = false,
                    ),
                )
            },
            onUnsupported = { notSupportedAction() },
        )
    }

    fun videoOverflow(fileName: String, virtual: Boolean = false) = BrowseOverflowActions(
        kind = BrowseOverflowKind.Video,
        onPlay = { playVideo(fileName) },
        onExternalPlayer = { openExternalFile(fileName) },
        onCopyUrl = { copySmbVideoUrl(fileName) },
        onOpenWith = { openExternalFile(fileName, usePreferredPlayer = false) },
        onSaveAs = { saveSmbFile(fileName) },
        onShare = { shareSmbFile(fileName) },
        onShareViaHttp = smbHttpShareFile(fileName),
        onOpenFolder = {
            openBrowseFolder(
                FolderSearch.openFolderTarget(fileName, isDirectory = false, virtual = virtual),
            )
        },
        onUnsupported = { notSupportedAction() },
    )

    fun fileOverflow(fileName: String) = if (isHtmlFileName(fileName)) {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Webpage,
            onOpenInBrowser = { openSmbHtml(fileName, incognito = false) },
            onOpenIncognito = { openSmbHtml(fileName, incognito = true) },
            onCopyUrl = { copySmbHtmlUrl(fileName) },
            onOpenWith = { openExternalFile(fileName, asFile = true) },
            onSaveAs = { saveSmbFile(fileName) },
            onShare = { shareSmbFile(fileName) },
            onShareViaHttp = smbHttpShareFile(fileName),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(fileName, isDirectory = false))
            },
            onUnsupported = { notSupportedAction() },
        )
    } else if (isPdfOrEbookFileName(fileName)) {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Pdf,
            onPlay = { openInternalDocument(fileName) },
            onExternalPlayer = {
                val src = source
                if (src != null && isPdfFileName(fileName)) {
                    val remote = if (relativeDir.isEmpty()) {
                        fileName
                    } else {
                        SmbGateway.joinRelativePath(relativeDir, fileName)
                    }
                    launchIO {
                        recordCurrentBrowseFolderHistory(src.id)
                        LocalHistory.recordSmbFile(src.id, remote, title = fileName.substringAfterLast('/'))
                        try {
                            OpenPdfExternally.openSmb(
                                context = context,
                                sourceId = src.id,
                                remoteRelativeFile = remote,
                                displayName = fileName.substringAfterLast('/').substringAfterLast('\\'),
                            )
                        } catch (e: Throwable) {
                            if (e.isZipMemberTooLarge()) return@launchIO
                            snackbar(
                                context.getString(
                                    R.string.open_pdf_external_failed,
                                    e.message ?: e.toString(),
                                ),
                            )
                        }
                    }
                } else if (!isPdfFileName(fileName)) {
                    openExternalFile(fileName)
                }
            },
            onOpenWith = { openExternalFile(fileName, asFile = true) },
            onSaveAs = { saveSmbFile(fileName) },
            onShare = { shareSmbFile(fileName) },
            onShareViaHttp = smbHttpShareFile(fileName),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(fileName, isDirectory = false))
            },
            onUnsupported = { notSupportedAction() },
        )
    } else {
        BrowseOverflowActions(
            kind = BrowseOverflowKind.Common,
            onOpenWith = { openExternalFile(fileName, asFile = true) },
            onSaveAs = { saveSmbFile(fileName) },
            onShare = { shareSmbFile(fileName) },
            onShareViaHttp = smbHttpShareFile(fileName),
            onOpenFolder = {
                openBrowseFolder(FolderSearch.openFolderTarget(fileName, isDirectory = false))
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
                        // Virtual layers: list/grid + toggles only (no content-mode persist).
                        folder = if (virtual.isVirtual) null else folderId,
                        skipAncestorKeys = smbModeSkipAncestors,
                        hideContentModes = virtual.hideContentModes,
                    )
                    IconButton(
                        enabled = refreshEnabled,
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
                    BrowseEmptyHint(string(R.string.smb_listing_error, error!!))
                }
                displayEntries.isEmpty() && searchHits.isEmpty() && !searching -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                filteredEntries.isEmpty() && searchHits.isEmpty() && !searching &&
                    search.submittedKeyword.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    val dirKey = listedDir ?: relativeDir
                    // Old (disk-hydrated / unrefreshed) listings: disk thumbs OK, no network jobs.
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
                    val documents = sections.documents.sortedForBrowseFolderUi(
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
                        BrowseSession.smbListingKey(sourceId, dirKey),
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
                    fun zipMemberCover(relativeName: String, coverFileName: String?): BrowseCover? {
                        if (!browseZipAsDir) return null
                        val parts = ZipAsDirListing.zipAsDirCoverParts(
                            relativeDir,
                            relativeName,
                            coverFileName,
                        ) ?: return null
                        return BrowseCover.SmbZipMember(sourceId, parts.first, parts.second)
                    }
                    fun coverFor(entry: BrowseEntryRemote.FolderGallery): BrowseCover? {
                        zipMemberCover(entry.relativeName, entry.coverFileName)?.let { return it }
                        val fileName = entry.coverFileName ?: return null
                        val remote = if (entry.relativeName.isEmpty()) {
                            SmbGateway.joinRelativePath(relativeDir, fileName)
                        } else {
                            SmbGateway.joinRelativePath(
                                SmbGateway.joinRelativePath(relativeDir, entry.relativeName),
                                fileName,
                            )
                        }
                        return BrowseCover.Smb(sourceId, remote)
                    }
                    fun dirCoverFor(dir: BrowseEntryRemote.Directory): BrowseCover? {
                        zipMemberCover(dir.relativeName, dir.coverFileName)?.let { return it }
                        val fileName = dir.coverFileName ?: return null
                        val remote = SmbGateway.joinRelativePath(
                            SmbGateway.joinRelativePath(relativeDir, dir.relativeName),
                            fileName,
                        )
                        return BrowseCover.Smb(sourceId, remote)
                    }
                    fun archiveCoverFor(entry: BrowseEntryRemote.ArchiveGallery): BrowseCover? {
                        // ZIP/TAR/EPUB stream + solid RAR/7z + documents (lazy first-page extract).
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
                        return BrowseCover.SmbArchive(sourceId, remote)
                    }
                    fun searchHitKey(entry: BrowseEntryRemote): String = when (entry) {
                        is BrowseEntryRemote.Directory -> "d-${entry.relativeName}"
                        is BrowseEntryRemote.FolderGallery -> "g-${entry.relativeName}"
                        is BrowseEntryRemote.ArchiveGallery ->
                            "a-${entry.parentRelativeName}/${entry.fileName}"
                        is BrowseEntryRemote.VideoFile -> "v-${entry.fileName}"
                        is BrowseEntryRemote.RegularFile -> "f-${entry.fileName}"
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
                                is BrowseEntryRemote.Directory -> if (grid) {
                                    BrowseDirectoryGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        onClick = { enterDir(entry.relativeName, fromSearch = true) },
                                        onLongClick = {
                                            toggleDirFavorite(entry.relativeName, entry.coverFileName)
                                        },
                                        showFavoriteStar = isDirFavorite(entry.relativeName),
                                        cover = dirCoverFor(entry),
                                        showFolderThumb = browseFolderThumbs,
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        overflow = dirOverflow(entry.relativeName, entry.coverFileName, entry.virtual),
                                    )
                                } else {
                                    BrowseDirectoryRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        onClick = { enterDir(entry.relativeName, fromSearch = true) },
                                        onLongClick = {
                                            toggleDirFavorite(entry.relativeName, entry.coverFileName)
                                        },
                                        cover = dirCoverFor(entry),
                                        showFolderThumb = browseFolderThumbs,
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        sizeBytes = entry.size,
                                        typeLabel = browseZipAsDirTypeLabel(
                                            entry.relativeName,
                                            entry.name,
                                        ) ?: "Dir",
                                        overflow = dirOverflow(entry.relativeName, entry.coverFileName, entry.virtual),
                                        showFavoriteStar = isDirFavorite(entry.relativeName),
                                    )
                                }
                                is BrowseEntryRemote.FolderGallery -> if (grid) {
                                    BrowseFolderGalleryGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        pageCount = entry.pageCount,
                                        pageCountCapped = entry.pageCountCapped,
                                        cover = coverFor(entry),
                                        progressGid = folderEntryProgressGid(entry),
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
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
                                        cover = coverFor(entry),
                                        progressGid = folderEntryProgressGid(entry),
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
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
                                is BrowseEntryRemote.ArchiveGallery -> if (grid) {
                                    BrowseArchiveGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        cover = archiveCoverFor(entry),
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openArchive(entry) },
                                        onLongClick = { openArchiveSecondary(entry) },
                                        overflow = archiveOverflow(entry),
                                    )
                                } else {
                                    BrowseArchiveGalleryRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        cover = archiveCoverFor(entry),
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openArchive(entry) },
                                        onLongClick = { openArchiveSecondary(entry) },
                                        fileName = entry.fileName,
                                        sizeBytes = entry.size,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        pageCount = entry.pageCount,
                                        showPages = showGalleryPages,
                                        overflow = archiveOverflow(entry),
                                    )
                                }
                                is BrowseEntryRemote.VideoFile -> if (grid) {
                                    BrowseVideoGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        thumbnailSource = VideoThumbnailSource.Smb(
                                            sourceId = sourceId,
                                            remoteRelativeFile = joinRemoteArchivePath(
                                                relativeDir,
                                                "",
                                                entry.fileName,
                                            ),
                                            knownSizeBytes = entry.size,
                                        ),
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openVideoPrimary(entry.fileName) },
                                        onLongClick = { openVideoSecondary(entry.fileName) },
                                        overflow = videoOverflow(entry.fileName, entry.virtual),
                                    )
                                } else {
                                    BrowseVideoRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        thumbnailSource = VideoThumbnailSource.Smb(
                                            sourceId = sourceId,
                                            remoteRelativeFile = joinRemoteArchivePath(
                                                relativeDir,
                                                "",
                                                entry.fileName,
                                            ),
                                            knownSizeBytes = entry.size,
                                        ),
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openVideoPrimary(entry.fileName) },
                                        onLongClick = { openVideoSecondary(entry.fileName) },
                                        fileName = entry.fileName,
                                        sizeBytes = entry.size,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        overflow = videoOverflow(entry.fileName, entry.virtual),
                                    )
                                }
                                is BrowseEntryRemote.RegularFile -> {
                                    val isImage = isImageFileName(
                                        entry.fileName.substringAfterLast('/'),
                                    )
                                    if (grid) {
                                        if (isImage) {
                                            BrowsePhotoGridImageItem(
                                                modifier = itemMod,
                                                name = entry.name,
                                                cover = imageCoverFor(entry),
                                                showPhotoThumb = true,
                                                thumbRetryKey = refreshToken,
                                                allowRemoteFetch = allowRemoteThumbs,
                                                onClick = { openFolderImage(entry) },
                                                onLongClick = { openExternalFile(entry.fileName) },
                                                overflow = fileOverflow(entry.fileName),
                                            )
                                        } else {
                                            BrowseFileGridItem(
                                                modifier = itemMod,
                                                name = entry.name,
                                                onClick = { openExternalFile(entry.fileName) },
                                                onLongClick = { openExternalFile(entry.fileName) },
                                                overflow = fileOverflow(entry.fileName),
                                            )
                                        }
                                    } else {
                                        BrowseFileRow(
                                            modifier = itemMod,
                                            name = entry.name,
                                            cover = if (isImage) imageCoverFor(entry) else null,
                                            showPhotoThumb = isImage,
                                            thumbRetryKey = refreshToken,
                                            allowRemoteFetch = allowRemoteThumbs,
                                            onClick = {
                                                if (isImage) {
                                                    openFolderImage(entry)
                                                } else {
                                                    openExternalFile(entry.fileName)
                                                }
                                            },
                                            onLongClick = { openExternalFile(entry.fileName) },
                                            fileName = entry.fileName,
                                            sizeBytes = entry.size,
                                            lastModifiedMs = entry.lastModifiedMs,
                                            overflow = fileOverflow(entry.fileName),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    fun LazyGridScope.documentSection(grid: Boolean) {
                        if (documents.isEmpty()) return
                        item(key = "hdr-docs", span = { GridItemSpan(maxLineSpan) }) {
                            BrowseSectionHeader(
                                stringResource(R.string.browse_documents),
                                onClick = { toggleSection(BrowseFolderSection.Documents) },
                            )
                        }
                        if (BrowseFolderSection.Documents in collapsedSections) return
                        items(
                            documents,
                            key = { entry ->
                                when (entry) {
                                    is BrowseEntryRemote.ArchiveGallery ->
                                        "a-${entry.parentRelativeName}/${entry.fileName}"
                                    is BrowseEntryRemote.RegularFile -> "f-${entry.fileName}"
                                    else -> "x-${entry.name}"
                                }
                            },
                        ) { entry ->
                            val itemMod = Modifier.thenIf(animateItems) { animateItem() }
                            when (entry) {
                                is BrowseEntryRemote.ArchiveGallery -> if (grid) {
                                    BrowseArchiveGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        cover = archiveCoverFor(entry),
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openArchive(entry) },
                                        onLongClick = { openArchiveSecondary(entry) },
                                        overflow = archiveOverflow(entry),
                                    )
                                } else {
                                    BrowseArchiveGalleryRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        cover = archiveCoverFor(entry),
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openArchive(entry) },
                                        onLongClick = { openArchiveSecondary(entry) },
                                        fileName = entry.fileName,
                                        sizeBytes = entry.size,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        pageCount = entry.pageCount,
                                        showPages = showGalleryPages,
                                        overflow = archiveOverflow(entry),
                                    )
                                }
                                is BrowseEntryRemote.RegularFile -> if (grid) {
                                    BrowseFileGridItem(
                                        modifier = itemMod,
                                        name = entry.name,
                                        onClick = { openExternalFile(entry.fileName) },
                                        onLongClick = { openExternalFile(entry.fileName) },
                                        overflow = fileOverflow(entry.fileName),
                                    )
                                } else {
                                    BrowseFileRow(
                                        modifier = itemMod,
                                        name = entry.name,
                                        thumbRetryKey = refreshToken,
                                        allowRemoteFetch = allowRemoteThumbs,
                                        onClick = { openExternalFile(entry.fileName) },
                                        onLongClick = { openExternalFile(entry.fileName) },
                                        fileName = entry.fileName,
                                        sizeBytes = entry.size,
                                        lastModifiedMs = entry.lastModifiedMs,
                                        overflow = fileOverflow(entry.fileName),
                                    )
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (photoGrid) {
                        val progressGid = stableGalleryId(sourceId, "smb:$relativeDir")
                        val gridState = rememberSmbPhotoGridState(
                            sourceId = sourceId,
                            relativeDir = "$dirKey#pg",
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
                            items(folderImages, key = { "pg-${it.fileName}" }) { file ->
                                BrowsePhotoGridImageItem(
                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                    name = file.name,
                                    cover = imageCoverFor(file),
                                    showPhotoThumb = true,
                                    thumbRetryKey = refreshToken,
                                    allowRemoteFetch = allowRemoteThumbs,
                                    onClick = { openFolderImage(file) },
                                    onLongClick = { openExternalFile(file.fileName) },
                                    overflow = fileOverflow(file.fileName),
                                )
                            }
                        }
                    } else if (useGrid) {
                        val gridState = rememberSmbBrowseGridState(sourceId, dirKey, scrollLayoutKey)
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
                                            overflow = dirOverflow(dir.relativeName, dir.coverFileName, dir.virtual),
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
                                                    progressGid = folderEntryProgressGid(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    showPages = showGalleryPages,
                                                    onClick = { openFolderGalleryPrimary(entry) },
                                                    onLongClick = { openFolderGallerySecondary(entry) },
                                                    overflow = folderGalleryOverflow(entry),
                                                )
                                            is BrowseEntryRemote.ArchiveGallery ->
                                                BrowseArchiveGridItem(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    cover = archiveCoverFor(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    onClick = { openArchive(entry) },
                                                    onLongClick = { openArchiveSecondary(entry) },
                                                    overflow = archiveOverflow(entry),
                                                )
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            documentSection(grid = true)
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
                                            thumbnailSource = VideoThumbnailSource.Smb(
                                                sourceId = sourceId,
                                                remoteRelativeFile = joinRemoteArchivePath(relativeDir, "", video.fileName),
                                                knownSizeBytes = video.size,
                                            ),
                                            allowRemoteFetch = allowRemoteThumbs,
                                            onClick = { openVideoPrimary(video.fileName) },
                                            onLongClick = { openVideoSecondary(video.fileName) },
                                            overflow = videoOverflow(video.fileName, video.virtual),
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
                                                thumbRetryKey = refreshToken,
                                                allowRemoteFetch = allowRemoteThumbs,
                                                onClick = { openFolderImage(file) },
                                                onLongClick = { openExternalFile(file.fileName) },
                                                overflow = fileOverflow(file.fileName),
                                            )
                                        } else {
                                            BrowseFileGridItem(
                                                modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                name = file.name,
                                                onClick = { openExternalFile(file.fileName) },
                                                onLongClick = { openExternalFile(file.fileName) },
                                                overflow = fileOverflow(file.fileName),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val listState = rememberSmbBrowseGridState(sourceId, dirKey, scrollLayoutKey)
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
                                            sizeBytes = dir.size,
                                            typeLabel = browseZipAsDirTypeLabel(dir.relativeName, dir.name) ?: "Dir",
                                            overflow = dirOverflow(dir.relativeName, dir.coverFileName, dir.virtual),
                                            showFavoriteStar = isDirFavorite(dir.relativeName),
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
                                                BrowseFolderGalleryRow(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    pageCount = entry.pageCount,
                                                    pageCountCapped = entry.pageCountCapped,
                                                    cover = coverFor(entry),
                                                    progressGid = folderEntryProgressGid(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    showPages = showGalleryPages,
                                                    onClick = { openFolderGalleryPrimary(entry) },
                                                    onLongClick = { openFolderGallerySecondary(entry) },
                                                    lastModifiedMs = entry.lastModifiedMs,
                                                    sizeBytes = entry.size,
                                                    typeLabel = browseZipAsDirTypeLabel(entry.relativeName, entry.name) ?: "Folder",
                                                    overflow = folderGalleryOverflow(entry),
                                                )
                                            is BrowseEntryRemote.ArchiveGallery ->
                                                BrowseArchiveGalleryRow(
                                                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                                                    name = entry.name,
                                                    cover = archiveCoverFor(entry),
                                                    thumbRetryKey = refreshToken,
                                                    allowRemoteFetch = allowRemoteThumbs,
                                                    onClick = { openArchive(entry) },
                                                    onLongClick = { openArchiveSecondary(entry) },
                                                    fileName = entry.fileName,
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
                            documentSection(grid = false)
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
                                        BrowseVideoRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = video.name,
                                            thumbnailSource = VideoThumbnailSource.Smb(
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
                                            overflow = videoOverflow(video.fileName, video.virtual),
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
                                        BrowseFileRow(
                                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                                            name = file.name,
                                            cover = if (isImage) imageCoverFor(file) else null,
                                            showPhotoThumb = isImage,
                                            thumbRetryKey = refreshToken,
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
                                            overflow = fileOverflow(file.fileName),
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
