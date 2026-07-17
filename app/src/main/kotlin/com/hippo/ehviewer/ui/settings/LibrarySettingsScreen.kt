package com.hippo.ehviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.displayNameForTreeUri
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.displayPath
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar
import moe.tarsin.string

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LibrarySettingsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    val scanning by LocalLibrary.scanning.collectAsState()
    val context = LocalContext.current
    val cannotGetLocation = stringResource(id = R.string.settings_download_cant_get_download_location)

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_library)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(
                        onClick = { launchIO { LocalLibrary.rescanAll() } },
                        enabled = !scanning && roots.isNotEmpty(),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
                    }
                    IconButton(
                        onClick = {
                            try {
                                pickRoot.launch(null)
                            } catch (_: ActivityNotFoundException) {
                                launch { snackbar(string(R.string.error_cant_find_activity)) }
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.library_add_root))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        if (roots.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.library_no_roots),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(paddingValues)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .fillMaxSize(),
            ) {
                items(roots, key = { it.id }) { root ->
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
        }
    }
}
