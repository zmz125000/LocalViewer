package com.hippo.ehviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.displayNameForTreeUri
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.SmbEditDialog
import com.hippo.ehviewer.ui.screen.SmbEditorState
import com.hippo.ehviewer.ui.screen.toDuplicateEditorState
import com.hippo.ehviewer.ui.screen.toEditorState
import com.hippo.ehviewer.util.displayPath
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import moe.tarsin.snackbar
import moe.tarsin.string

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Manage local library folders (SAF) and SMB network sources in one place.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LibrarySettingsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    val smbSources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val scanning by LocalLibrary.scanning.collectAsState()
    val context = LocalContext.current
    val cannotGetLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.browse_manage_sources)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(
                        onClick = { launchIO { LocalLibrary.rescanAll() } },
                        enabled = !scanning && roots.isNotEmpty(),
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
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .fillMaxSize(),
        ) {
            item(key = "hdr-folder") {
                BrowseSectionHeader(stringResource(R.string.folder))
            }
            item(key = "add-folder") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_add_root)) },
                    leadingContent = {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    trailingContent = {
                        IconButton(onClick = { launchSafPicker() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.library_add_root))
                        }
                    },
                )
            }
            if (roots.isEmpty()) {
                item(key = "folder-empty") {
                    Text(
                        text = stringResource(R.string.library_no_roots),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(roots, key = { "r-${it.id}" }) { root ->
                    ListItem(
                        headlineContent = { Text(root.displayName) },
                        supportingContent = {
                            Text(text = root.treeUri.toUri().displayPath ?: root.treeUri)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { launchIO { LocalLibrary.removeRoot(root) } },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.library_remove_root),
                                )
                            }
                        },
                    )
                }
            }

            item(key = "hdr-smb") {
                BrowseSectionHeader(stringResource(R.string.network))
            }
            item(key = "add-smb") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.network_add_smb)) },
                    leadingContent = {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { smbEditor = SmbEditorState() },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.network_add_smb))
                        }
                    },
                )
            }
            if (smbSources.isEmpty()) {
                item(key = "smb-empty") {
                    Text(
                        text = stringResource(R.string.network_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            } else {
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
            }
        }
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
