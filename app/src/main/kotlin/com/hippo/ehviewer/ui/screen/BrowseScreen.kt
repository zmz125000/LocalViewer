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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.displayNameForTreeUri
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
 * Hub for Network (SMB) and local Folder roots.
 * FAB = quick-add (SAF or SMB). Top-right = manage both source types.
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

    var showQuickAdd by remember { mutableStateOf(false) }
    var smbEditor by remember { mutableStateOf<SmbEditorState?>(null) }

    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
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
                LocalLibrary.addRoot(treeUri.toString(), name)
            }.onFailure {
                logcat(it)
                launch { snackbar(cannotGetLocation) }
            }
        }
    }

    fun launchSafPicker() {
        try {
            pickRoot.launch(null)
        } catch (_: ActivityNotFoundException) {
            launch { snackbar(string(R.string.error_cant_find_activity)) }
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
        navigate(FolderBrowserScreenDestination)
    }

    fun openSmb(source: SmbSourceEntity) {
        BrowseSession.setSmbSegments(source.id, emptyList())
        navigate(SmbBrowserScreenDestination(source.id, ""))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse)) },
                actions = {
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
        floatingActionButton = {
            FloatingActionButton(onClick = { showQuickAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.browse_quick_add))
            }
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
                            subtitle = "\\\\${source.host}\\${source.share}",
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
                            subtitle = stringResource(R.string.library_gallery_folder),
                            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
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
                            supportingContent = {
                                Text(
                                    buildString {
                                        append("\\\\${source.host}\\${source.share}")
                                        if (source.pathPrefix.isNotBlank()) {
                                            append("\\")
                                            append(source.pathPrefix.replace('/', '\\'))
                                        }
                                    },
                                )
                            },
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
                                Text(stringResource(R.string.library_gallery_folder))
                            },
                            leadingContent = {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.fillMaxWidth().clickable { openLocalRoot(root) },
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showQuickAdd) {
        AlertDialog(
            onDismissRequest = { showQuickAdd = false },
            title = { Text(stringResource(R.string.browse_quick_add)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.library_add_root)) },
                        supportingContent = { Text(stringResource(R.string.browse_quick_add_folder_hint)) },
                        leadingContent = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            showQuickAdd = false
                            launchSafPicker()
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.network_add_smb)) },
                        supportingContent = { Text(stringResource(R.string.browse_quick_add_smb_hint)) },
                        leadingContent = {
                            Icon(Icons.Default.Lan, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            showQuickAdd = false
                            smbEditor = SmbEditorState()
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuickAdd = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    smbEditor?.let { state ->
        SmbEditDialog(
            state = state,
            onDismiss = { smbEditor = null },
            onSave = { saved, password ->
                launchIO {
                    if (saved.id == 0L) {
                        SmbRepository.add(
                            displayName = saved.displayName,
                            host = saved.host,
                            port = saved.port.toIntOrNull() ?: 445,
                            share = saved.share,
                            pathPrefix = saved.path,
                            username = saved.username,
                            domain = saved.domain,
                            password = password,
                        )
                    } else {
                        val existing = SmbRepository.load(saved.id)
                        SmbRepository.update(
                            SmbSourceEntity(
                                id = saved.id,
                                displayName = saved.displayName.ifBlank { saved.host },
                                host = saved.host.trim(),
                                port = saved.port.toIntOrNull() ?: 445,
                                share = saved.share.trim().trim('/'),
                                pathPrefix = saved.path.trim().trim('/'),
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
                launch {
                    val entity = SmbSourceEntity(
                        id = testState.id,
                        displayName = testState.displayName,
                        host = testState.host.trim(),
                        port = testState.port.toIntOrNull() ?: 445,
                        share = testState.share.trim().trim('/'),
                        pathPrefix = testState.path.trim().trim('/'),
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
