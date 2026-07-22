package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.smb.SmbPasswordStore

data class SmbEditorState(
    val id: Long = 0,
    val displayName: String = "",
    val host: String = "",
    val port: String = "445",
    /** Combined share + optional subpath, e.g. `Media` or `Media/Books`. Empty = server/share root. */
    val sharePath: String = "",
    val username: String = "",
    val domain: String = "",
    val password: String = "",
)

/**
 * Parse a combined share/path string.
 * First segment is the share name; the rest is the path within the share.
 * Empty input → empty share and path (browse at SMB root / require share when listing).
 */
fun parseSharePath(input: String): Pair<String, String> {
    val normalized = input.trim()
        .removePrefix("\\\\")
        .trimStart('\\', '/')
        .replace('\\', '/')
        .trim('/')
    if (normalized.isEmpty()) return "" to ""
    val parts = normalized.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "" to ""
    val share = parts.first()
    val path = parts.drop(1).joinToString("/")
    return share to path
}

fun formatSharePath(share: String, pathPrefix: String): String {
    val s = share.trim().trim('/', '\\')
    val p = pathPrefix.trim().trim('/', '\\').replace('\\', '/')
    return when {
        s.isEmpty() && p.isEmpty() -> ""
        p.isEmpty() -> s
        s.isEmpty() -> p
        else -> "$s/$p"
    }
}

fun SmbEditorState.resolvedDisplayName(): String = displayName.trim().ifBlank { host.trim() }

fun SmbEditorState.resolvedShareAndPath(): Pair<String, String> = parseSharePath(sharePath)

fun SmbSourceEntity.toEditorState(includePassword: Boolean = true) = SmbEditorState(
    id = id,
    displayName = displayName,
    host = host,
    port = port.toString(),
    sharePath = formatSharePath(share, pathPrefix),
    username = username,
    domain = domain,
    password = if (includePassword) SmbPasswordStore.get(id) else "",
)

/** Duplicate as a new source (id = 0) with same fields and password. */
fun SmbSourceEntity.toDuplicateEditorState() = toEditorState(includePassword = true).copy(
    id = 0,
    displayName = "$displayName (copy)",
)

@Composable
fun SmbEditDialog(
    state: SmbEditorState,
    onDismiss: () -> Unit,
    onSave: (SmbEditorState, String) -> Unit,
    onDelete: (Long) -> Unit,
    onTest: (SmbEditorState, String) -> Unit,
) {
    var displayName by remember { mutableStateOf(state.displayName) }
    var host by remember { mutableStateOf(state.host) }
    var port by remember { mutableStateOf(state.port) }
    var sharePath by remember { mutableStateOf(state.sharePath) }
    var username by remember { mutableStateOf(state.username) }
    var domain by remember { mutableStateOf(state.domain) }
    var password by remember { mutableStateOf(state.password) }
    // Default off for new adds. For edit, only on if already guest/blank credentials.
    var anonymous by remember {
        mutableStateOf(
            state.id != 0L &&
                (state.username.isBlank() || state.username.equals("guest", ignoreCase = true)),
        )
    }

    val focusManager = LocalFocusManager.current
    val hostFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val shareFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val domainFocus = remember { FocusRequester() }

    fun clearFocus() = focusManager.clearFocus()

    fun current(): SmbEditorState {
        val anon = anonymous
        return state.copy(
            displayName = displayName,
            host = host,
            port = port,
            sharePath = sharePath,
            username = if (anon) "" else username,
            domain = if (anon) "" else domain,
            password = if (anon) "" else password,
        )
    }

    fun currentPassword(): String = if (anonymous) "" else password

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
                    label = { Text(stringResource(R.string.network_display_name_optional)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { hostFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.network_host)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .focusRequester(hostFocus),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.network_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { shareFocus.requestFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .focusRequester(portFocus),
                )
                OutlinedTextField(
                    value = sharePath,
                    onValueChange = { sharePath = it },
                    label = { Text(stringResource(R.string.network_share_path)) },
                    supportingText = { Text(stringResource(R.string.network_share_path_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (anonymous) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { userFocus.requestFocus() },
                        onDone = { clearFocus() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .focusRequester(shareFocus),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.network_anonymous_login))
                    }
                    Switch(
                        checked = anonymous,
                        onCheckedChange = { checked ->
                            anonymous = checked
                            if (checked) {
                                // Keep previous values in state only while toggled off; clear focus.
                                clearFocus()
                            }
                        },
                    )
                }
                if (!anonymous) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.network_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .focusRequester(userFocus),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.network_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { domainFocus.requestFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .focusRequester(passFocus),
                    )
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text(stringResource(R.string.network_domain)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .focusRequester(domainFocus),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(current(), currentPassword()) },
                enabled = host.isNotBlank() && sharePath.isNotBlank(),
            ) {
                Text(stringResource(R.string.network_save))
            }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = { onTest(current(), currentPassword()) },
                    enabled = host.isNotBlank() && sharePath.isNotBlank(),
                ) {
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
