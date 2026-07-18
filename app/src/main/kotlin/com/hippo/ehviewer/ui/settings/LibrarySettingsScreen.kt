package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.SmbEditDialog
import com.hippo.ehviewer.ui.screen.SmbEditorState
import com.hippo.ehviewer.ui.screen.resolvedDisplayName
import com.hippo.ehviewer.ui.screen.resolvedShareAndPath
import com.hippo.ehviewer.ui.screen.toDuplicateEditorState
import com.hippo.ehviewer.ui.screen.toEditorState
import com.hippo.ehviewer.util.displayPath
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import moe.tarsin.snackbar
import moe.tarsin.string

/**
 * Manage library (scanned), browse-only folders, and SMB network sources.
 * Adding sources is done from the Browse top bar.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LibrarySettingsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val libraryRoots by LocalLibrary.libraryRootsFlow().collectAsState(initial = emptyList())
    val folderRoots by LocalLibrary.folderOnlyRootsFlow().collectAsState(initial = emptyList())
    val smbSources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val scanning by LocalLibrary.scanning.collectAsState()
    var smbEditor by remember { mutableStateOf<SmbEditorState?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.browse_manage_sources)) },
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
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .fillMaxSize(),
        ) {
            item(key = "hdr-library") {
                BrowseSectionHeader(stringResource(R.string.library))
            }
            if (libraryRoots.isEmpty()) {
                item(key = "library-empty") {
                    Text(
                        text = stringResource(R.string.library_no_roots),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(libraryRoots, key = { "lib-${it.id}" }) { root ->
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

            item(key = "hdr-folder") {
                BrowseSectionHeader(stringResource(R.string.folder))
            }
            if (folderRoots.isEmpty()) {
                item(key = "folder-empty") {
                    Text(
                        text = stringResource(R.string.folder_no_browse_roots),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(folderRoots, key = { "fol-${it.id}" }) { root ->
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
            if (smbSources.isEmpty()) {
                item(key = "smb-empty") {
                    Text(
                        text = stringResource(R.string.network_empty),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
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
            },
        )
    }
}
