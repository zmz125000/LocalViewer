package com.hippo.ehviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_FOLDER
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.AddRootResult
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.MediaPermissions
import com.hippo.ehviewer.library.displayNameForTreeUri
import com.hippo.ehviewer.library.isMediaStoreRootUri
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.SmbEditDialog
import com.hippo.ehviewer.ui.screen.SmbEditorState
import com.hippo.ehviewer.ui.screen.WebDavEditDialog
import com.hippo.ehviewer.ui.screen.WebDavEditorState
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.hippo.ehviewer.ui.screen.resolvedDisplayName
import com.hippo.ehviewer.ui.screen.resolvedShareAndPath
import com.hippo.ehviewer.ui.screen.toDuplicateEditorState
import com.hippo.ehviewer.ui.screen.toEditorState
import com.hippo.ehviewer.util.LocalNetworkPermission
import com.hippo.ehviewer.util.displayPath
import com.hippo.ehviewer.util.ensureLocalNetworkPermission
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import moe.tarsin.snackbar
import moe.tarsin.string

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Manage library (scanned), browse-only folders, and SMB network sources.
 * Each section has an “Add …” row; Browse top bar can also add sources.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LibrarySettingsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val libraryRoots by LocalLibrary.libraryRootsFlow().collectAsState(initial = emptyList())
    val folderRoots by LocalLibrary.folderOnlyRootsFlow().collectAsState(initial = emptyList())
    val smbSources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val webDavSources by WebDavRepository.sourcesFlow().collectAsState(initial = emptyList())
    val scanning by LocalLibrary.scanning.collectAsState()
    var smbEditor by remember { mutableStateOf<SmbEditorState?>(null) }
    var webDavEditor by remember { mutableStateOf<WebDavEditorState?>(null) }
    val context = LocalContext.current
    val cannotGetLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
    val alreadyAdded = stringResource(id = R.string.library_root_already_added)
    val permissionDenied = stringResource(id = R.string.source_media_permission_denied)
    val deviceMediaName = stringResource(id = R.string.source_device_media_name)
    var pendingSafRole by remember { mutableIntStateOf(LIBRARY_ROOT_ROLE_LIBRARY) }
    var accessChooserRole by remember { mutableStateOf<Int?>(null) }
    var mediaDenied by remember { mutableStateOf(false) }
    var openSafAfterMediaPerm by remember { mutableStateOf(false) }

    /** After media permission grant, switch this SAF root to MediaStore mode. */
    var pendingMediaModeRootId by remember { mutableLongStateOf(-1L) }

    androidx.compose.runtime.LaunchedEffect(mediaDenied) {
        if (!mediaDenied) return@LaunchedEffect
        try {
            snackbar(permissionDenied)
        } finally {
            mediaDenied = false
        }
    }

    fun addDeviceMediaLibrary(role: Int) {
        // Match SAF: IO job + non-cancellable scan in LocalLibrary (not LaunchedEffect).
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

    val mediaPermission = com.hippo.ehviewer.ui.screen.rememberMediaPermissionLauncher(
        onGranted = { role ->
            val pendingRootId = pendingMediaModeRootId
            if (pendingRootId >= 0L) {
                pendingMediaModeRootId = -1L
                launchIO {
                    LocalLibrary.setRootAccessMode(pendingRootId, LIBRARY_ROOT_ACCESS_MEDIA)
                }
            } else if (openSafAfterMediaPerm) {
                openSafAfterMediaPerm = false
                openSafPicker()
            } else {
                addDeviceMediaLibrary(role)
            }
        },
        onDenied = {
            if (pendingMediaModeRootId >= 0L) {
                pendingMediaModeRootId = -1L
                mediaDenied = true
            } else if (openSafAfterMediaPerm) {
                openSafAfterMediaPerm = false
                openSafPicker()
            } else {
                mediaDenied = true
            }
        },
    )

    fun toggleRootAccessMode(root: LibraryRootEntity) {
        // Pure device-media roots have no SAF tree — always MediaStore.
        if (isMediaStoreRootUri(root.treeUri)) return
        if (root.includesArchives) {
            // Switch to MediaStore — need media permission first.
            if (MediaPermissions.hasCompleteMediaAccess(context)) {
                launchIO {
                    LocalLibrary.setRootAccessMode(root.id, LIBRARY_ROOT_ACCESS_MEDIA)
                }
            } else {
                pendingMediaModeRootId = root.id
                mediaPermission.request(root.role)
            }
        } else {
            launchIO {
                LocalLibrary.setRootAccessMode(root.id, LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE)
            }
        }
    }

    fun launchSafPicker(role: Int) {
        pendingSafRole = role
        if (MediaPermissions.shouldRequestMediaPermissionForSafAdd(context)) {
            openSafAfterMediaPerm = true
            mediaPermission.request(role)
        } else {
            openSafPicker()
        }
    }

    fun launchAddLocalSource(role: Int) {
        // Device media is one root:
        // - as Library → skip chooser for library and folder add → SAF only
        // - as Folder only → skip chooser only for folder add; library add still shows chooser
        val mediaAsLibrary = libraryRoots.any { isMediaStoreRootUri(it.treeUri) }
        val mediaAsFolder = folderRoots.any { isMediaStoreRootUri(it.treeUri) }
        val skipChooser = when {
            mediaAsLibrary -> true
            mediaAsFolder && role == LIBRARY_ROOT_ROLE_FOLDER -> true
            else -> false
        }
        if (skipChooser) {
            launchSafPicker(role)
        } else {
            accessChooserRole = role
        }
    }

    accessChooserRole?.let { role ->
        com.hippo.ehviewer.ui.screen.LocalSourceAccessDialog(
            role = role,
            onDismiss = { accessChooserRole = null },
            onChooseSaf = { launchSafPicker(it) },
            onChooseDeviceMedia = { mediaPermission.request(it) },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.browse_manage_sources)) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(
                        onClick = { launchIO { LocalLibrary.rescanAll() } },
                        enabled = !scanning && libraryRoots.isNotEmpty(),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
        ) {
            item(key = "hdr-library") {
                BrowseSectionHeader(stringResource(R.string.library))
            }
            items(libraryRoots, key = { "lib-${it.id}" }) { root ->
                ListItem(
                    headlineContent = { Text(root.displayName) },
                    supportingContent = {
                        Text(
                            text = if (isMediaStoreRootUri(root.treeUri)) {
                                stringResource(R.string.source_access_device_media)
                            } else {
                                root.treeUri.toUri().displayPath ?: root.treeUri
                            },
                        )
                    },
                    trailingContent = {
                        LocalRootTrailingActions(
                            root = root,
                            onToggleAccessMode = { toggleRootAccessMode(root) },
                            onDelete = { launchIO { LocalLibrary.removeRoot(root) } },
                        )
                    },
                )
            }
            item(key = "add-library") {
                AddSourceRow(
                    title = stringResource(R.string.library_add_library_source),
                    onClick = { launchAddLocalSource(LIBRARY_ROOT_ROLE_LIBRARY) },
                )
            }

            item(key = "hdr-folder") {
                BrowseSectionHeader(stringResource(R.string.folder))
            }
            items(folderRoots, key = { "fol-${it.id}" }) { root ->
                ListItem(
                    headlineContent = { Text(root.displayName) },
                    supportingContent = {
                        Text(
                            text = if (isMediaStoreRootUri(root.treeUri)) {
                                stringResource(R.string.source_access_device_media)
                            } else {
                                root.treeUri.toUri().displayPath ?: root.treeUri
                            },
                        )
                    },
                    trailingContent = {
                        LocalRootTrailingActions(
                            root = root,
                            onToggleAccessMode = { toggleRootAccessMode(root) },
                            onDelete = { launchIO { LocalLibrary.removeRoot(root) } },
                        )
                    },
                )
            }
            item(key = "add-folder") {
                AddSourceRow(
                    title = stringResource(R.string.library_add_folder_source),
                    onClick = { launchAddLocalSource(LIBRARY_ROOT_ROLE_FOLDER) },
                )
            }

            item(key = "hdr-smb") {
                BrowseSectionHeader(stringResource(R.string.network))
            }
            items(smbSources, key = { "s-${it.id}" }) { source ->
                ListItem(
                    headlineContent = { Text(source.displayName) },
                    supportingContent = {
                        Text(
                            buildString {
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
                            },
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { smbEditor = source.toDuplicateEditorState() },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.network_duplicate_smb),
                                )
                            }
                            IconButton(
                                onClick = { smbEditor = source.toEditorState() },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.network_edit_smb),
                                )
                            }
                            IconButton(
                                onClick = { launchIO { SmbRepository.delete(source) } },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.library_remove_root),
                                )
                            }
                        }
                    },
                )
            }
            item(key = "add-smb") {
                AddSourceRow(
                    title = stringResource(R.string.network_add_smb),
                    onClick = { smbEditor = SmbEditorState() },
                )
            }

            item(key = "hdr-webdav") {
                BrowseSectionHeader(stringResource(R.string.webdav))
            }
            items(webDavSources, key = { "w-${it.id}" }) { source ->
                ListItem(
                    headlineContent = { Text(source.displayName) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(source.baseUrl.trimEnd('/'))
                                if (source.pathPrefix.isNotBlank()) {
                                    append('/')
                                    append(source.pathPrefix.trim('/'))
                                }
                            },
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { webDavEditor = source.toDuplicateEditorState() },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.network_duplicate_smb),
                                )
                            }
                            IconButton(
                                onClick = { webDavEditor = source.toEditorState() },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.webdav_edit),
                                )
                            }
                            IconButton(
                                onClick = { launchIO { WebDavRepository.delete(source) } },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.library_remove_root),
                                )
                            }
                        }
                    },
                )
            }
            item(key = "add-webdav") {
                AddSourceRow(
                    title = stringResource(R.string.webdav_add),
                    onClick = { webDavEditor = WebDavEditorState() },
                )
            }
        }
    }

    smbEditor?.let { state ->
        SmbEditDialog(
            state = state,
            onDismiss = { smbEditor = null },
            onSave = { saved, password ->
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
            },
            onDelete = { id ->
                launchIO {
                    SmbRepository.load(id)?.let { SmbRepository.delete(it) }
                }
                smbEditor = null
            },
            onTest = { testState, password ->
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
            },
        )
    }

    webDavEditor?.let { state ->
        WebDavEditDialog(
            state = state,
            onDismiss = { webDavEditor = null },
            onSave = { saved, password ->
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
                            WebDavSourceEntity(
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
            },
            onTest = { testState, password ->
                launch {
                    if (!ensureLocalNetworkPermission()) {
                        snackbar(string(R.string.network_test_fail, LocalNetworkPermission.deniedMessage()))
                        return@launch
                    }
                    val entity = WebDavSourceEntity(
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
            },
            onDelete = {
                launchIO {
                    WebDavRepository.load(state.id)?.let { WebDavRepository.delete(it) }
                }
                webDavEditor = null
            }.takeIf { state.id != 0L },
        )
    }
}

@Composable
private fun LocalRootTrailingActions(
    root: LibraryRootEntity,
    onToggleAccessMode: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDeviceMedia = isMediaStoreRootUri(root.treeUri)
    val mediaMode = root.prefersMediaStore
    Row {
        // Pic = MediaStore only; archive box = file access (images + local archives).
        // Device-media roots have no SAF backup — button shows media icon, disabled.
        IconButton(
            onClick = onToggleAccessMode,
            enabled = !isDeviceMedia,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = if (mediaMode) Icons.Default.PhotoLibrary else Icons.Default.Inventory2,
                contentDescription = stringResource(
                    if (mediaMode) {
                        R.string.source_access_mode_media
                    } else {
                        R.string.source_access_mode_media_archive
                    },
                ),
            )
        }
        IconButton(
            onClick = onDelete,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.library_remove_root),
            )
        }
    }
}

@Composable
private fun AddSourceRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
