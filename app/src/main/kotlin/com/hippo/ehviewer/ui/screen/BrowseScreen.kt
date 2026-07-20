package com.hippo.ehviewer.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_FOLDER
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.AddRootResult
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.displayNameForTreeUri
import com.hippo.ehviewer.library.isMediaStoreRootUri
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.LibrarySettingsScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Hub for library/folder SAF roots and SMB network sources.
 * Top bar: add library, add browse folder, add SMB, manage sources.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.BrowseScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    val smbSources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val gridView by Settings.gridView.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    val cannotGetLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
    val alreadyAdded = stringResource(id = R.string.library_root_already_added)

    var smbEditor by remember { mutableStateOf<SmbEditorState?>(null) }
    // Pending role for the next OpenDocumentTree result.
    var pendingSafRole by remember { mutableIntStateOf(LIBRARY_ROOT_ROLE_LIBRARY) }
    var accessChooserRole by remember { mutableStateOf<Int?>(null) }
    var pendingMediaRole by remember { mutableStateOf<Int?>(null) }
    var mediaDenied by remember { mutableStateOf(false) }
    val permissionDenied = stringResource(id = R.string.source_media_permission_denied)
    val deviceMediaName = stringResource(id = R.string.source_device_media_name)

    // Permission callbacks are outside Screen context receivers — apply results here.
    // Clear state only AFTER work finishes: clearing the LaunchedEffect key first would
    // cancel addMediaStoreRoot mid-scan (SAF uses launchIO and does not hit this bug).
    androidx.compose.runtime.LaunchedEffect(pendingMediaRole) {
        val role = pendingMediaRole ?: return@LaunchedEffect
        try {
            when (LocalLibrary.addMediaStoreRoot(deviceMediaName, role)) {
                is AddRootResult.Created, is AddRootResult.UpgradedToLibrary -> Unit
                is AddRootResult.AlreadyExists -> snackbar(alreadyAdded)
            }
        } finally {
            pendingMediaRole = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(mediaDenied) {
        if (!mediaDenied) return@LaunchedEffect
        try {
            snackbar(permissionDenied)
        } finally {
            mediaDenied = false
        }
    }

    val mediaPermission = rememberMediaPermissionLauncher(
        onGranted = { role -> pendingMediaRole = role },
        onDenied = { mediaDenied = true },
    )

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

    fun launchSafPicker(role: Int) {
        pendingSafRole = role
        try {
            pickRoot.launch(null)
        } catch (_: ActivityNotFoundException) {
            launch { snackbar(string(R.string.error_cant_find_activity)) }
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
            ),
        )
        navigate(FolderBrowserScreenDestination())
    }

    fun openSmb(source: SmbSourceEntity) {
        if (source.share.isBlank()) {
            launch { snackbar(string(R.string.network_share_required)) }
            return
        }
        BrowseSession.setSmbSegments(source.id, emptyList())
        navigate(SmbBrowserScreenDestination(source.id, ""))
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
                )
            } else {
                val existing = SmbRepository.load(saved.id)
                SmbRepository.update(
                    SmbSourceEntity(
                        id = saved.id,
                        displayName = saved.resolvedDisplayName(),
                        host = saved.host.trim(),
                        port = saved.port.toIntOrNull() ?: 445,
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
            val entity = SmbSourceEntity(
                id = testState.id,
                displayName = testState.resolvedDisplayName(),
                host = testState.host.trim(),
                port = testState.port.toIntOrNull() ?: 445,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse)) },
                actions = {
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
                        onClick = { launchAddLocalSource(LIBRARY_ROOT_ROLE_FOLDER) },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(R.string.library_add_folder_source),
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
        val empty = roots.isEmpty() && smbSources.isEmpty()
        if (empty) {
            BrowseEmptyHint(
                text = stringResource(R.string.browse_empty),
                modifier = Modifier.padding(padding),
            )
        } else if (gridView) {
            FastScrollLazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (smbSources.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "hdr-net") {
                        BrowseSectionHeader(stringResource(R.string.network))
                    }
                    items(smbSources, key = { "s-${it.id}" }) { source ->
                        BrowseRootCard(
                            title = source.displayName,
                            subtitle = smbSubtitle(source),
                            icon = { Icon(Icons.Default.Lan, contentDescription = null) },
                            onClick = { openSmb(source) },
                        )
                    }
                }
                if (roots.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "hdr-fol") {
                        BrowseSectionHeader(stringResource(R.string.folder))
                    }
                    items(roots, key = { "r-${it.id}" }) { root ->
                        BrowseRootCard(
                            title = root.displayName,
                            subtitle = stringResource(
                                if (root.isLibraryRole) {
                                    R.string.library
                                } else {
                                    R.string.folder
                                },
                            ),
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
                        )
                    }
                }
            }
        } else {
            FastScrollLazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .fillMaxSize(),
            ) {
                if (smbSources.isNotEmpty()) {
                    item(key = "hdr-net") {
                        BrowseSectionHeader(stringResource(R.string.network))
                    }
                    items(smbSources, key = { "s-${it.id}" }) { source ->
                        ListItem(
                            headlineContent = { Text(source.displayName) },
                            supportingContent = { Text(smbSubtitle(source)) },
                            leadingContent = {
                                Icon(Icons.Default.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.fillMaxWidth().clickable { openSmb(source) },
                        )
                    }
                }
                if (roots.isNotEmpty()) {
                    item(key = "hdr-fol") {
                        BrowseSectionHeader(stringResource(R.string.folder))
                    }
                    items(roots, key = { "r-${it.id}" }) { root ->
                        ListItem(
                            headlineContent = { Text(root.displayName) },
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
                            modifier = Modifier.fillMaxWidth().clickable { openLocalRoot(root) },
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
private fun BrowseRootCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    com.ehviewer.core.ui.component.ElevatedCard(
        onClick = onClick,
        onLongClick = {},
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
