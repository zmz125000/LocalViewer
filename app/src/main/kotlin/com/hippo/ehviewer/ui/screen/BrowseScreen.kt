package com.hippo.ehviewer.ui.screen

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.LibrarySettingsScreenDestination
import com.hippo.ehviewer.ui.destinations.NetworkScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.navigate

/**
 * Hub for Network (SMB) and local Folder roots.
 * Tapping a root opens the existing hierarchical dir browser for that source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.BrowseScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    val smbSources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    val gridView by Settings.gridView.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_library))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigate(NetworkScreenDestination) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.network_add_smb))
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
