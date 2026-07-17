package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NetworkScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)
    val sources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())
    var editor by remember { mutableStateOf<SmbEditorState?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editor = SmbEditorState()
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.network_add_smb))
            }
        },
    ) { padding ->
        if (sources.isEmpty()) {
            BrowseEmptyHint(
                stringResource(R.string.network_empty),
                modifier = Modifier.padding(padding),
            )
        } else {
            FastScrollLazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .fillMaxSize(),
            ) {
                items(sources, key = { it.id }) { source ->
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
                                    source.lastError?.let {
                                        append("\n")
                                        append(it)
                                    }
                                },
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Lan, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    editor = SmbEditorState(
                                        id = source.id,
                                        displayName = source.displayName,
                                        host = source.host,
                                        port = source.port.toString(),
                                        share = source.share,
                                        path = source.pathPrefix,
                                        username = source.username,
                                        domain = source.domain,
                                        password = SmbPasswordStore.get(source.id),
                                    )
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.network_edit_smb))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navigate(SmbBrowserScreenDestination(source.id, ""))
                            },
                    )
                }
            }
        }
    }

    editor?.let { state ->
        SmbEditDialog(
            state = state,
            onDismiss = { editor = null },
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
                editor = null
            },
            onDelete = { id ->
                launchIO {
                    SmbRepository.load(id)?.let { SmbRepository.delete(it) }
                }
                editor = null
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

private data class SmbEditorState(
    val id: Long = 0,
    val displayName: String = "",
    val host: String = "",
    val port: String = "445",
    val share: String = "",
    val path: String = "",
    val username: String = "",
    val domain: String = "",
    val password: String = "",
)

@Composable
private fun SmbEditDialog(
    state: SmbEditorState,
    onDismiss: () -> Unit,
    onSave: (SmbEditorState, String) -> Unit,
    onDelete: (Long) -> Unit,
    onTest: (SmbEditorState, String) -> Unit,
) {
    var displayName by remember { mutableStateOf(state.displayName) }
    var host by remember { mutableStateOf(state.host) }
    var port by remember { mutableStateOf(state.port) }
    var share by remember { mutableStateOf(state.share) }
    var path by remember { mutableStateOf(state.path) }
    var username by remember { mutableStateOf(state.username) }
    var domain by remember { mutableStateOf(state.domain) }
    var password by remember { mutableStateOf(state.password) }

    fun current() = state.copy(
        displayName = displayName,
        host = host,
        port = port,
        share = share,
        path = path,
        username = username,
        domain = domain,
        password = password,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (state.id == 0L) R.string.network_add_smb else R.string.network_edit_smb,
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.network_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.network_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.network_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text(stringResource(R.string.network_share)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.network_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.network_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.network_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.network_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(current(), password) },
                enabled = host.isNotBlank() && share.isNotBlank(),
            ) {
                Text(stringResource(R.string.network_save))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { onTest(current(), password) }) {
                    Text(stringResource(R.string.network_test))
                }
                if (state.id != 0L) {
                    TextButton(onClick = { onDelete(state.id) }) {
                        Text(stringResource(R.string.network_delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}
