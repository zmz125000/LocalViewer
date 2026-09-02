package com.hippo.ehviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LocalFolderListing
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.SMB_FOLDER_TOKEN
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FOLDER_TOKEN
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.buildLocalBrowseStack
import com.hippo.ehviewer.library.isVideoFileName
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.library.resolveRelative
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.library.withLocalZipCentralDirectory
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.main.HistoryDirectoryGridItem
import com.hippo.ehviewer.ui.main.HistoryGridItem
import com.hippo.ehviewer.ui.main.HistoryListItem
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToLocalZipFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.navToSmbFolderReader
import com.hippo.ehviewer.ui.navToSmbStreamArchiveReader
import com.hippo.ehviewer.ui.navToWebDavFolderReader
import com.hippo.ehviewer.ui.navToWebDavStreamArchiveReader
import com.hippo.ehviewer.ui.openFromHistoryWithBackStack
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.hippo.ehviewer.ui.openLocalFolderPhotoGrid
import com.hippo.ehviewer.ui.openSmbBrowseDir
import com.hippo.ehviewer.ui.openSmbFolderPhotoGrid
import com.hippo.ehviewer.ui.openWebDavBrowseDir
import com.hippo.ehviewer.ui.openWebDavFolderPhotoGrid
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path.Companion.toPath

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
    val filteredHistory = remember(allHistory, filterQuery) {
        if (filterQuery.isEmpty()) {
            allHistory
        } else {
            allHistory.filter { info ->
                info.title?.contains(filterQuery, ignoreCase = true) == true ||
                    info.titleJpn?.contains(filterQuery, ignoreCase = true) == true
            }
        }
    }
    // Browse-dir pins live in a capped top section; everything else stays in the main list.
    val allDirectoryItems = remember(filteredHistory) {
        filteredHistory.filter { LocalHistory.isBrowseDirectory(it) }
    }
    val historyItems = remember(filteredHistory) {
        filteredHistory.filterNot { LocalHistory.isBrowseDirectory(it) }
    }

    val listMode by Settings.listMode.collectAsState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)
    val gridColumnCount = GalleryGridDefaults.columnCount()
    // Collapsed: list max 10 / grid max two rows. Expanded: full dir pin list.
    var directoriesExpanded by rememberSaveable { mutableStateOf(false) }
    val directoryCollapsedLimit = remember(listMode, gridColumnCount) {
        if (listMode == 0) HISTORY_DIRECTORY_LIST_LIMIT else gridColumnCount * HISTORY_DIRECTORY_GRID_ROWS
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
                        val zipOpen = ZipPaths.parseGallery(local.contentPath)
                        if (zipOpen != null) {
                            val (zipAbs, inner) = zipOpen
                            val names = withIOContext {
                                withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                                    ZipAsDirListing.directImageNames(cd, inner)
                                }.orEmpty()
                            }
                            if (names.isEmpty()) {
                                snackbar(string(R.string.browse_open_failed))
                                return@launch
                            }
                            val gi = local.toBaseGalleryInfo()
                            val parentRel = ZipAsDirListing.parentBrowseRelative(local.relativePath)
                            val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                            val rootPath = root?.let { LocalLibrary.rootPath(it) }
                            openFromHistoryWithBackStack(
                                pushParentDir = {
                                    if (root != null && rootPath != null) {
                                        openLocalBrowseDir(
                                            rootId = root.id,
                                            rootDisplayName = root.displayName,
                                            rootPath = rootPath,
                                            relativePath = parentRel,
                                            preferMediaStore = root.prefersMediaStore,
                                            fromHistory = true,
                                        )
                                    }
                                },
                                openContent = {
                                    navToLocalZipFolderReader(zipAbs, inner, names, gi)
                                },
                            )
                            return@launch
                        }
                        if (Settings.photoGridMode.value) {
                            val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                            val rootPath = root?.let { LocalLibrary.rootPath(it) }
                            if (root == null || rootPath == null) {
                                navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                            } else {
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
                        } else {
                            navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                        }
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
                    // Re-open → bump this dir pin to the top of Directories.
                    withIOContext {
                        LocalHistory.recordLocalBrowseFolder(
                            rootId = root.id,
                            relativePath = target.relativePath,
                            title = info.title ?: root.displayName,
                            thumbKey = info.thumbKey,
                        )
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
                is LocalHistoryTarget.SmbBrowseFolder -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    withIOContext {
                        LocalHistory.recordSmbBrowseFolder(
                            sourceId = source.id,
                            relativePath = target.relativePath,
                            title = info.title ?: source.displayName,
                            thumbKey = info.thumbKey,
                        )
                    }
                    openSmbBrowseDir(
                        sourceId = source.id,
                        remoteDir = target.relativePath,
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.WebDavBrowseFolder -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    withIOContext {
                        LocalHistory.recordWebDavBrowseFolder(
                            sourceId = source.id,
                            relativePath = target.relativePath,
                            title = info.title ?: source.displayName,
                            thumbKey = info.thumbKey,
                        )
                    }
                    openWebDavBrowseDir(
                        sourceId = source.id,
                        remoteDir = target.relativePath,
                        fromHistory = true,
                    )
                }
                is LocalHistoryTarget.LocalFolderGallery -> {
                    // Photo-grid mode: tap opens image grid. Else reader (+ optional parent stack).
                    val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val zipGallery = ZipAsDirListing.parseZipGalleryRelative(target.relativePath)
                    if (zipGallery != null) {
                        val (zipRel, inner) = zipGallery
                        val zipAbs = rootPath.resolveRelative(zipRel).toString()
                        val galleryDir = ZipAsDirListing.virtualRelativeDir(zipRel, inner)
                        val names = withIOContext {
                            FolderGalleryIndex.loadLocal(
                                target.rootId,
                                LocalFolderListing.rootConfigKey(
                                    rootPath,
                                    root.prefersMediaStore,
                                ),
                                galleryDir,
                            ) ?: withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                                ZipAsDirListing.directImageNames(cd, inner)
                            }.orEmpty()
                        }
                        val gi = BaseGalleryInfo(
                            gid = info.gid,
                            token = LOCAL_FOLDER_TOKEN,
                            title = info.title ?: zipRel.substringAfterLast('/'),
                            pages = names.size,
                            favoriteSlot = NOT_FAVORITED,
                            rating = -1f,
                            thumbKey = info.thumbKey,
                            uploader = "${target.rootId}\u0000${target.relativePath.trim('/')}",
                            category = 0,
                        )
                        val parentRel = ZipAsDirListing.parentBrowseRelative(target.relativePath)
                        openFromHistoryWithBackStack(
                            pushParentDir = {
                                openLocalBrowseDir(
                                    rootId = root.id,
                                    rootDisplayName = root.displayName,
                                    rootPath = rootPath,
                                    relativePath = parentRel,
                                    preferMediaStore = root.prefersMediaStore,
                                    fromHistory = true,
                                )
                            },
                            openContent = {
                                navToLocalZipFolderReader(zipAbs, inner, names, gi)
                            },
                        )
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
                    // Same identity as browse openFolderGallery so progress stubs keep path.
                    val rel = target.relativePath.trim('/')
                    val title = info.title ?: rel.substringAfterLast('/').ifEmpty { "Folder" }
                    if (Settings.photoGridMode.value) {
                        openLocalFolderPhotoGrid(
                            rootId = root.id,
                            rootDisplayName = root.displayName,
                            rootPath = rootPath,
                            relativePath = target.relativePath,
                            preferMediaStore = root.prefersMediaStore,
                            title = title,
                            fromHistory = true,
                        )
                        return@launch
                    }
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = LOCAL_FOLDER_TOKEN,
                        title = title,
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                        uploader = "${target.rootId}\u0000$rel",
                        category = 0,
                    )
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            openLocalBrowseDir(
                                rootId = root.id,
                                rootDisplayName = root.displayName,
                                rootPath = rootPath,
                                relativePath = parentRel,
                                preferMediaStore = root.prefersMediaStore,
                                fromHistory = true,
                            )
                        },
                        openContent = { navToLocalFolderReader(galleryPath, gi) },
                    )
                }
                is LocalHistoryTarget.SmbFolderGallery -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remoteDir.trim('/')
                    if (Settings.photoGridMode.value) {
                        openSmbFolderPhotoGrid(
                            sourceId = source.id,
                            remoteDir = remote,
                            fromHistory = true,
                        )
                        return@launch
                    }
                    val parentRel = parentRelativeOfFile(remote)
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = SMB_FOLDER_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/').ifEmpty { "Share" },
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                        uploader = "${source.id}\u0000$remote",
                        category = 2,
                    )
                    val names = FolderGalleryIndex.loadSmb(
                        source.id,
                        SmbGateway.sourceConfigKey(source),
                        remote,
                    ).orEmpty()
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            openSmbBrowseDir(
                                sourceId = source.id,
                                remoteDir = parentRel,
                                fromHistory = true,
                            )
                        },
                        // Same names the folder view would pass; empty → reader uses index again.
                        openContent = { navToSmbFolderReader(source.id, remote, names, gi) },
                    )
                }
                is LocalHistoryTarget.WebDavFolderGallery -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remoteDir.trim('/')
                    if (Settings.photoGridMode.value) {
                        openWebDavFolderPhotoGrid(
                            sourceId = source.id,
                            remoteDir = remote,
                            fromHistory = true,
                        )
                        return@launch
                    }
                    val parentRel = parentRelativeOfFile(remote)
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = WEBDAV_FOLDER_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/').ifEmpty { "WebDAV" },
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                        uploader = "${source.id}\u0000$remote",
                        category = 3,
                    )
                    val names = FolderGalleryIndex.loadWebDav(
                        source.id,
                        WebDavGateway.sourceConfigKey(source),
                        remote,
                    ).orEmpty()
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            openWebDavBrowseDir(
                                sourceId = source.id,
                                remoteDir = parentRel,
                                fromHistory = true,
                            )
                        },
                        openContent = { navToWebDavFolderReader(source.id, remote, names, gi) },
                    )
                }
                is LocalHistoryTarget.LocalArchive -> {
                    // Optional parent browse path under alwaysExitToDir
                    // (fromHistory FAB still jumps straight to History).
                    val parent = withIOContext {
                        LocalLibrary.resolveArchiveBrowseParent(target.path)
                    }
                    val parentPrefersMediaStore = if (parent != null) {
                        withIOContext { LocalLibrary.loadRoot(parent.rootId)?.prefersMediaStore } ?: false
                    } else {
                        false
                    }
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            if (parent != null) {
                                openLocalBrowseDir(
                                    rootId = parent.rootId,
                                    rootDisplayName = parent.rootDisplayName,
                                    rootPath = parent.rootPath,
                                    relativePath = parent.parentRelativePath,
                                    preferMediaStore = parentPrefersMediaStore,
                                    fromHistory = true,
                                )
                            }
                        },
                        openContent = { navToReader(target.path) },
                    )
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
                    // Always smba: (progress gid). Drop legacy smb-archive: history row if present.
                    val progressGid = stableGalleryId(source.id, "smba:$remote")
                    if (info.gid != progressGid) {
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                    }
                    val gi = BaseGalleryInfo(
                        gid = progressGid,
                        token = SMB_ARCHIVE_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey ?: HistoryThumbKey.smbArchive(source.id, remote),
                        uploader = "${source.id}\u0000$remote",
                        category = 1,
                    )
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            openSmbBrowseDir(
                                sourceId = source.id,
                                remoteDir = parentRel,
                                fromHistory = true,
                            )
                        },
                        openContent = { navToSmbStreamArchiveReader(source.id, remote, gi) },
                    )
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
                    val progressGid = stableGalleryId(source.id, "dava:$remote")
                    if (info.gid != progressGid) {
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                    }
                    val gi = BaseGalleryInfo(
                        gid = progressGid,
                        token = WEBDAV_ARCHIVE_TOKEN,
                        title = info.title ?: remote.substringAfterLast('/'),
                        pages = info.pages,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey ?: HistoryThumbKey.webdavArchive(source.id, remote),
                        uploader = "${source.id}\u0000$remote",
                        category = 1,
                    )
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            openWebDavBrowseDir(
                                sourceId = source.id,
                                remoteDir = parentRel,
                                fromHistory = true,
                            )
                        },
                        openContent = { navToWebDavStreamArchiveReader(source.id, remote, gi) },
                    )
                }
                // Videos / regular files: open or play in place; stay on History so
                // leaving the external app returns here (no helper browse stack).
                is LocalHistoryTarget.LocalFile -> {
                    val path = target.path
                    val name = info.title
                        ?: path.substringAfterLast('/').substringAfterLast('\\')
                    val mime = mimeTypeForFileName(name)
                    val isVideo = isVideoFileName(name) || isVideoFileName(path)
                    withIOContext {
                        LocalHistory.recordLocalFile(path, title = name, thumbKey = info.thumbKey)
                        try {
                            if (isVideo && Settings.useMedia3Player.value) {
                                OpenFileExternally.playLocal(context, path, displayName = name, mimeType = mime)
                            } else {
                                OpenFileExternally.openLocal(context, path, displayName = name, mimeType = mime)
                            }
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                }
                is LocalHistoryTarget.SmbFile -> {
                    val source = withIOContext { SmbRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remotePath.trim('/')
                    val name = info.title
                        ?: remote.substringAfterLast('/').substringAfterLast('\\')
                    val mime = mimeTypeForFileName(name)
                    val isVideo = isVideoFileName(name) || isVideoFileName(remote)
                    withIOContext {
                        LocalHistory.recordSmbFile(
                            source.id,
                            remote,
                            title = name,
                            thumbKey = info.thumbKey,
                        )
                        try {
                            if (isVideo && Settings.useMedia3Player.value) {
                                OpenFileExternally.playSmb(
                                    context,
                                    source.id,
                                    remote,
                                    displayName = name,
                                    mimeType = mime,
                                )
                            } else {
                                OpenFileExternally.openSmb(
                                    context,
                                    source.id,
                                    remote,
                                    displayName = name,
                                    mimeType = mime,
                                )
                            }
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                }
                is LocalHistoryTarget.WebDavFile -> {
                    val source = withIOContext { WebDavRepository.load(target.sourceId) }
                    if (source == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    val remote = target.remotePath.trim('/')
                    val name = info.title
                        ?: remote.substringAfterLast('/').substringAfterLast('\\')
                    val mime = mimeTypeForFileName(name)
                    val isVideo = isVideoFileName(name) || isVideoFileName(remote)
                    withIOContext {
                        LocalHistory.recordWebDavFile(
                            source.id,
                            remote,
                            title = name,
                            thumbKey = info.thumbKey,
                        )
                        try {
                            if (isVideo && Settings.useMedia3Player.value) {
                                OpenFileExternally.playWebDav(
                                    context,
                                    source.id,
                                    remote,
                                    displayName = name,
                                    mimeType = mime,
                                )
                            } else {
                                OpenFileExternally.openWebDav(
                                    context,
                                    source.id,
                                    remote,
                                    displayName = name,
                                    mimeType = mime,
                                )
                            }
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                }
                is LocalHistoryTarget.Orphan -> {
                    // Legacy "local" browse rows without path metadata, or foreign EH history.
                    val local = withIOContext { LocalLibrary.loadGallery(target.gid) }
                    if (local != null) {
                        if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                            navToReader(local.contentPath)
                        } else if (Settings.photoGridMode.value) {
                            val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                            val rootPath = root?.let { LocalLibrary.rootPath(it) }
                            if (root == null || rootPath == null) {
                                navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                            } else {
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
            val listState = rememberLazyListState()
            // Match browse folder list: no extra horizontal marginH (ListItem inset only).
            val listPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + marginV,
                bottom = paddingValues.calculateBottomPadding() + marginV,
            )
            FastScrollLazyColumn(
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
                            )
                        }
                    }
                    // Gap under dirs: tap toggles expand/collapse when there is overflow.
                    if (historyItems.isNotEmpty() || canExpandDirectories) {
                        item(key = "dir-gap") {
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
                        )
                    }
                    // Gap under dirs: tap toggles expand/collapse when there is overflow.
                    if (historyItems.isNotEmpty() || canExpandDirectories) {
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
                    )
                }
            }
        }

        if (directoryItems.isEmpty() && historyItems.isEmpty()) {
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

/** Max browse-dir pins shown in History list mode (collapsed). */
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
