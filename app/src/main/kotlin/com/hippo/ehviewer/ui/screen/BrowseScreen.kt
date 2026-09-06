package com.hippo.ehviewer.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_FOLDER
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.util.rememberInVM
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.easytier.EasyTierRuntime
import com.hippo.ehviewer.library.AddRootResult
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.MediaPermissions
import com.hippo.ehviewer.library.displayNameForTreeUri
import com.hippo.ehviewer.library.isMediaStoreRootUri
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.EasyTierScreenDestination
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.LibrarySettingsScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.WebDavBrowserScreenDestination
import com.hippo.ehviewer.ui.easytier.EasyTierDialog
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.util.LocalNetworkPermission
import com.hippo.ehviewer.util.ensureLocalNetworkPermission
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import kotlinx.coroutines.launch
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Hub for library/folder SAF roots and SMB / WebDAV network sources.
 * Top bar: add library, add browse folder, add SMB, add WebDAV, manage sources.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.BrowseScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    // Survive NavHost dispose/restore (enter a source → back).
    // collectAsState(initial=empty) remounted empty lists for one frame and
    // coerced LazyList scroll to top; VM-held state keeps last data + scroll.
    val listState = rememberInVM { LazyListState() }
    val gridState = rememberInVM { LazyGridState() }
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
    val favoriteKeys by Settings.favoriteBrowseSources.collectAsState()
    val gridView by Settings.gridView.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    val cannotGetLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
    val alreadyAdded = stringResource(id = R.string.library_root_already_added)
    val addedToFavourites = stringResource(id = R.string.add_to_favourites)
    val removedFromFavourites = stringResource(id = R.string.remove_from_favourites)

    fun notifyFavoriteToggle(nowFavorite: Boolean) {
        // launch {
        //    snackbar(if (nowFavorite) addedToFavourites else removedFromFavourites)
        // }
    }

    var smbEditor by remember { mutableStateOf<SmbEditorState?>(null) }
    var showEasyTierDialog by remember { mutableStateOf(false) }
    val easyTierState by EasyTierRuntime.state.collectAsState()
    var webDavEditor by remember { mutableStateOf<WebDavEditorState?>(null) }
    // Pending role for the next OpenDocumentTree result.
    var pendingSafRole by remember { mutableIntStateOf(LIBRARY_ROOT_ROLE_LIBRARY) }
    var accessChooserRole by remember { mutableStateOf<Int?>(null) }
    var mediaDenied by remember { mutableStateOf(false) }

    /** After media-permission dialog for SAF add: open picker whether granted or denied. */
    var openSafAfterMediaPerm by remember { mutableStateOf(false) }
    val permissionDenied = stringResource(id = R.string.source_media_permission_denied)
    val deviceMediaName = stringResource(id = R.string.source_device_media_name)

    androidx.compose.runtime.LaunchedEffect(mediaDenied) {
        if (!mediaDenied) return@LaunchedEffect
        try {
            snackbar(permissionDenied)
        } finally {
            mediaDenied = false
        }
    }

    fun addDeviceMediaLibrary(role: Int) {
        // Same launchIO path as SAF: never tie the MediaStore scan to LaunchedEffect keys
        // (composition cancel left an empty library until manual rescan).
        launchIO {
            when (LocalLibrary.addMediaStoreRoot(deviceMediaName, role)) {
                is AddRootResult.Created, is AddRootResult.UpgradedToLibrary -> Unit
                is AddRootResult.AlreadyExists -> launch { snackbar(alreadyAdded) }
            }
        }
    }

    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        val role = pendingSafRole
        launchIO {
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, URI_FLAGS)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                val path = documentUri.toOkioPath()
                check(path.isDirectory) { "$path is not a directory" }
                val name = context.displayNameForTreeUri(treeUri.toString())
                // Always store SAF tree (backup). Runtime upgrade to MediaStore is gated by setting.
                when (LocalLibrary.addRoot(treeUri.toString(), name, role)) {
                    is AddRootResult.Created, is AddRootResult.UpgradedToLibrary -> Unit
                    is AddRootResult.AlreadyExists -> launch { snackbar(alreadyAdded) }
                }
            }.onFailure {
                logcat(it)
                launch { snackbar(cannotGetLocation) }
            }
        }
    }

    fun openSafPicker() {
        try {
            pickRoot.launch(null)
        } catch (_: ActivityNotFoundException) {
            launch { snackbar(string(R.string.error_cant_find_activity)) }
        }
    }

    val mediaPermission = rememberMediaPermissionLauncher(
        onGranted = { role ->
            if (openSafAfterMediaPerm) {
                openSafAfterMediaPerm = false
                openSafPicker()
            } else {
                addDeviceMediaLibrary(role)
            }
        },
        onDenied = {
            if (openSafAfterMediaPerm) {
                // Still open SAF — upgrade stays off without permission.
                openSafAfterMediaPerm = false
                openSafPicker()
            } else {
                mediaDenied = true
            }
        },
    )

    fun launchSafPicker(role: Int) {
        pendingSafRole = role
        // When "Prefer device media" is on, ask media permission so SAF trees can upgrade.
        // Off = pure SAF (privacy), skip the prompt.
        if (MediaPermissions.shouldRequestMediaPermissionForSafAdd(context)) {
            openSafAfterMediaPerm = true
            mediaPermission.request(role)
        } else {
            openSafPicker()
        }
    }

    fun launchAddLocalSource(role: Int) {
        // Device media is one root:
        // - as Library → no need to offer it again for library or folder add → SAF only
        // - as Folder only → skip chooser for folder add; library add still shows chooser
        //   (device media can upgrade folder → library)
        val mediaRoot = roots.firstOrNull { isMediaStoreRootUri(it.treeUri) }
        val skipChooser = when {
            mediaRoot == null -> false
            mediaRoot.isLibraryRole -> true
            role == LIBRARY_ROOT_ROLE_FOLDER -> true
            else -> false
        }
        if (skipChooser) {
            launchSafPicker(role)
        } else {
            accessChooserRole = role
        }
    }

    fun openLocalRoot(root: LibraryRootEntity) {
        val path = LocalLibrary.rootPath(root) ?: return
        BrowseSession.localStack = listOf(
            BrowseSession.LocalFrame(
                rootId = root.id,
                path = path.toString(),
                title = root.displayName,
                relativePath = "",
                preferMediaStore = root.prefersMediaStore,
            ),
        )
        navigate(FolderBrowserScreenDestination())
    }

    fun openSmb(source: SmbSourceEntity) {
        BrowseSession.setSmbSegments(source.id, emptyList())
        navigate(SmbBrowserScreenDestination(source.id, ""))
    }

    fun openWebDav(source: com.ehviewer.core.database.model.WebDavSourceEntity) {
        BrowseSession.setWebDavSegments(source.id, emptyList())
        navigate(WebDavBrowserScreenDestination(source.id, ""))
    }

    fun saveWebDav(saved: WebDavEditorState, password: String) {
        launchIO {
            if (saved.id == 0L) {
                WebDavRepository.add(
                    displayName = saved.resolvedDisplayName(),
                    baseUrl = saved.baseUrl,
                    pathPrefix = saved.pathPrefix,
                    username = saved.username,
                    password = password,
                    easytierHost = saved.easytierHost,
                )
            } else {
                val existing = WebDavRepository.load(saved.id)
                WebDavRepository.update(
                    com.ehviewer.core.database.model.WebDavSourceEntity(
                        id = saved.id,
                        displayName = saved.resolvedDisplayName(),
                        baseUrl = saved.baseUrl.trim(),
                        easytierHost = saved.easytierHost.trim(),
                        pathPrefix = saved.pathPrefix,
                        username = saved.username,
                        addedAt = existing?.addedAt
                            ?: Clock.System.now().toEpochMilliseconds(),
                        lastOkAt = existing?.lastOkAt,
                        lastError = existing?.lastError,
                    ),
                    password = password,
                )
            }
        }
        webDavEditor = null
    }

    fun testWebDav(testState: WebDavEditorState, password: String) {
        launch {
            if (!ensureLocalNetworkPermission()) {
                snackbar(string(R.string.network_test_fail, LocalNetworkPermission.deniedMessage()))
                return@launch
            }
            val entity = com.ehviewer.core.database.model.WebDavSourceEntity(
                id = testState.id,
                displayName = testState.resolvedDisplayName(),
                baseUrl = testState.baseUrl.trim(),
                easytierHost = testState.easytierHost.trim(),
                pathPrefix = testState.pathPrefix,
                username = testState.username,
                addedAt = 0L,
            )
            val result = WebDavClient.testConnection(entity, password)
            if (result.isSuccess) {
                if (testState.id != 0L) WebDavRepository.markOk(testState.id)
                snackbar(string(R.string.network_test_ok))
            } else {
                val msg = result.exceptionOrNull()?.message ?: "error"
                if (testState.id != 0L) WebDavRepository.markError(testState.id, msg)
                snackbar(string(R.string.network_test_fail, msg))
            }
        }
    }

    fun saveSmb(saved: SmbEditorState, password: String) {
        val (share, pathPrefix) = saved.resolvedShareAndPath()
        launchIO {
            if (saved.id == 0L) {
                SmbRepository.add(
                    displayName = saved.resolvedDisplayName(),
                    host = saved.host,
                    port = saved.port.toIntOrNull() ?: 445,
                    share = share,
                    pathPrefix = pathPrefix,
                    username = saved.username,
                    domain = saved.domain,
                    password = password,
                    easytierHost = saved.easytierHost,
                )
            } else {
                val existing = SmbRepository.load(saved.id)
                SmbRepository.update(
                    SmbSourceEntity(
                        id = saved.id,
                        displayName = saved.resolvedDisplayName(),
                        host = saved.host.trim(),
                        port = saved.port.toIntOrNull() ?: 445,
                        easytierHost = saved.easytierHost.trim(),
                        share = share,
                        pathPrefix = pathPrefix,
                        username = saved.username,
                        domain = saved.domain,
                        addedAt = existing?.addedAt
                            ?: Clock.System.now().toEpochMilliseconds(),
                        lastOkAt = existing?.lastOkAt,
                        lastError = existing?.lastError,
                    ),
                    password = password,
                )
            }
        }
        smbEditor = null
    }

    fun testSmb(testState: SmbEditorState, password: String) {
        val (share, pathPrefix) = testState.resolvedShareAndPath()
        launch {
            if (!ensureLocalNetworkPermission()) {
                snackbar(string(R.string.network_test_fail, LocalNetworkPermission.deniedMessage()))
                return@launch
            }
            val entity = SmbSourceEntity(
                id = testState.id,
                displayName = testState.resolvedDisplayName(),
                host = testState.host.trim(),
                port = testState.port.toIntOrNull() ?: 445,
                easytierHost = testState.easytierHost.trim(),
                share = share,
                pathPrefix = pathPrefix,
                username = testState.username,
                domain = testState.domain,
                addedAt = 0L,
            )
            val result = SmbGateway.testConnection(entity, password)
            if (result.isSuccess) {
                if (testState.id != 0L) SmbRepository.markOk(testState.id)
                snackbar(string(R.string.network_test_ok))
            } else {
                val msg = result.exceptionOrNull()?.message ?: "error"
                if (testState.id != 0L) SmbRepository.markError(testState.id, msg)
                snackbar(string(R.string.network_test_fail, msg))
            }
        }
    }

    accessChooserRole?.let { role ->
        LocalSourceAccessDialog(
            role = role,
            onDismiss = { accessChooserRole = null },
            onChooseSaf = { launchSafPicker(it) },
            onChooseDeviceMedia = { mediaPermission.request(it) },
        )
    }

    // Zero content insets: NavigationRail already sits in a sibling Row, so default
    // Scaffold safeDrawing would re-apply the start system inset as a huge left gap.
    // Library/History (SearchBarScreen) already use contentWindowInsets = 0.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse)) },
                // Only status-bar top; start/end are handled by the rail / content edge.
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                actions = {
                    IconButton(
                        onClick = { showEasyTierDialog = true },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Default.Hub,
                            contentDescription = stringResource(R.string.settings_easytier),
                            tint = if (easyTierState.connectingOrRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    IconButton(
                        onClick = { launchAddLocalSource(LIBRARY_ROOT_ROLE_LIBRARY) },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.LibraryBooks,
                            contentDescription = stringResource(R.string.library_add_library_source),
                        )
                    }
                    IconButton(
                        onClick = { smbEditor = SmbEditorState() },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Default.Lan,
                            contentDescription = stringResource(R.string.network_add_smb),
                        )
                    }
                    IconButton(
                        onClick = { webDavEditor = WebDavEditorState() },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.webdav_add),
                        )
                    }
                    IconButton(
                        onClick = { navigate(LibrarySettingsScreenDestination) },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.browse_manage_sources),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val empty = roots.isEmpty() && smbSources.isEmpty() && webDavSources.isEmpty()
        if (empty) {
            BrowseEmptyHint(
                text = stringResource(R.string.browse_empty),
                modifier = Modifier.padding(padding),
            )
        } else if (gridView) {
            FastScrollLazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (smbSources.isNotEmpty() || webDavSources.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "hdr-net") {
                        BrowseSectionHeader(stringResource(R.string.network))
                    }
                    items(smbSources, key = { "s-${it.id}" }) { source ->
                        val favorited = BrowseFavorites.smbKey(source.id) in favoriteKeys
                        BrowseRootCard(
                            title = source.displayName,
                            subtitle = smbSubtitle(source),
                            favorited = favorited,
                            icon = { Icon(Icons.Default.Lan, contentDescription = null) },
                            onClick = { openSmb(source) },
                            onLongClick = {
                                notifyFavoriteToggle(BrowseFavorites.toggleSmb(source.id))
                            },
                        )
                    }
                    items(webDavSources, key = { "w-${it.id}" }) { source ->
                        val favorited = BrowseFavorites.webDavKey(source.id) in favoriteKeys
                        BrowseRootCard(
                            title = source.displayName,
                            subtitle = webDavSubtitle(source),
                            favorited = favorited,
                            icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                            onClick = { openWebDav(source) },
                            onLongClick = {
                                notifyFavoriteToggle(BrowseFavorites.toggleWebDav(source.id))
                            },
                        )
                    }
                }
                if (roots.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "hdr-fol") {
                        BrowseSectionHeader(stringResource(R.string.folder))
                    }
                    items(roots, key = { "r-${it.id}" }) { root ->
                        val favorited = BrowseFavorites.localKey(root.id) in favoriteKeys
                        BrowseRootCard(
                            title = root.displayName,
                            subtitle = stringResource(
                                if (root.isLibraryRole) {
                                    R.string.library
                                } else {
                                    R.string.folder
                                },
                            ),
                            favorited = favorited,
                            icon = {
                                Icon(
                                    if (root.isLibraryRole) {
                                        Icons.AutoMirrored.Filled.LibraryBooks
                                    } else {
                                        Icons.Default.Folder
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = { openLocalRoot(root) },
                            onLongClick = {
                                notifyFavoriteToggle(BrowseFavorites.toggleLocal(root.id))
                            },
                        )
                    }
                }
            }
        } else {
            FastScrollLazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .fillMaxSize(),
            ) {
                if (smbSources.isNotEmpty() || webDavSources.isNotEmpty()) {
                    item(key = "hdr-net") {
                        BrowseSectionHeader(stringResource(R.string.network))
                    }
                    items(smbSources, key = { "s-${it.id}" }) { source ->
                        val favorited = BrowseFavorites.smbKey(source.id) in favoriteKeys
                        ListItem(
                            headlineContent = {
                                BrowseSourceTitle(name = source.displayName, favorited = favorited)
                            },
                            supportingContent = { Text(smbSubtitle(source)) },
                            leadingContent = {
                                Icon(Icons.Default.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { openSmb(source) },
                                    onLongClick = {
                                        notifyFavoriteToggle(BrowseFavorites.toggleSmb(source.id))
                                    },
                                ),
                        )
                    }
                    items(webDavSources, key = { "w-${it.id}" }) { source ->
                        val favorited = BrowseFavorites.webDavKey(source.id) in favoriteKeys
                        ListItem(
                            headlineContent = {
                                BrowseSourceTitle(name = source.displayName, favorited = favorited)
                            },
                            supportingContent = { Text(webDavSubtitle(source)) },
                            leadingContent = {
                                Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { openWebDav(source) },
                                    onLongClick = {
                                        notifyFavoriteToggle(BrowseFavorites.toggleWebDav(source.id))
                                    },
                                ),
                        )
                    }
                }
                if (roots.isNotEmpty()) {
                    item(key = "hdr-fol") {
                        BrowseSectionHeader(stringResource(R.string.folder))
                    }
                    items(roots, key = { "r-${it.id}" }) { root ->
                        val favorited = BrowseFavorites.localKey(root.id) in favoriteKeys
                        ListItem(
                            headlineContent = {
                                BrowseSourceTitle(name = root.displayName, favorited = favorited)
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (root.isLibraryRole) R.string.library else R.string.folder,
                                    ),
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (root.isLibraryRole) {
                                        Icons.AutoMirrored.Filled.LibraryBooks
                                    } else {
                                        Icons.Default.Folder
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { openLocalRoot(root) },
                                    onLongClick = {
                                        notifyFavoriteToggle(BrowseFavorites.toggleLocal(root.id))
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }

    smbEditor?.let { state ->
        SmbEditDialog(
            state = state,
            onDismiss = { smbEditor = null },
            onSave = { saved, password -> saveSmb(saved, password) },
            onDelete = { id ->
                launchIO {
                    SmbRepository.load(id)?.let { SmbRepository.delete(it) }
                }
                smbEditor = null
            },
            onTest = { testState, password -> testSmb(testState, password) },
        )
    }

    if (showEasyTierDialog) {
        EasyTierDialog(
            onDismiss = { showEasyTierDialog = false },
            onOpenFullSettings = {
                showEasyTierDialog = false
                navigate(EasyTierScreenDestination)
            },
        )
    }
    webDavEditor?.let { state ->
        WebDavEditDialog(
            state = state,
            onDismiss = { webDavEditor = null },
            onSave = { saved, password -> saveWebDav(saved, password) },
            onTest = { testState, password -> testWebDav(testState, password) },
            onDelete = {
                launchIO {
                    WebDavRepository.load(state.id)?.let { WebDavRepository.delete(it) }
                }
                webDavEditor = null
            }.takeIf { state.id != 0L },
        )
    }
}

private fun webDavSubtitle(source: com.ehviewer.core.database.model.WebDavSourceEntity): String = buildString {
    append(source.baseUrl.trimEnd('/'))
    if (source.pathPrefix.isNotBlank()) {
        append('/')
        append(source.pathPrefix.trim('/'))
    }
}

private fun smbSubtitle(source: SmbSourceEntity): String = buildString {
    append("\\\\")
    append(source.host)
    if (source.share.isNotBlank()) {
        append("\\")
        append(source.share)
        if (source.pathPrefix.isNotBlank()) {
            append("\\")
            append(source.pathPrefix.replace('/', '\\'))
        }
    }
}

@Composable
private fun BrowseSourceTitle(
    name: String,
    favorited: Boolean,
    modifier: Modifier = Modifier,
) {
    // Inline star so PlaceholderVerticalAlign.TextCenter lines up with the
    // glyph center (not the taller line box that Row+CenterVertically uses).
    if (!favorited) {
        Text(name, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    val starId = "fav"
    val starTint = MaterialTheme.colorScheme.primary
    val starCd = stringResource(R.string.favourite)
    val text = buildAnnotatedString {
        append(name)
        append('\u00A0') // thin gap before star; stays with the last word
        appendInlineContent(starId, "[★]")
    }
    val inline = mapOf(
        starId to InlineTextContent(
            Placeholder(
                width = 18.sp,
                height = 18.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = starCd,
                modifier = Modifier.fillMaxSize(),
                tint = starTint,
            )
        },
    )
    Text(
        text = text,
        modifier = modifier,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        inlineContent = inline,
    )
}

@Composable
private fun BrowseRootCard(
    title: String,
    subtitle: String,
    favorited: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    com.ehviewer.core.ui.component.ElevatedCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            BrowseSourceTitle(name = title, favorited = favorited)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
