package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

    fun current() = state.copy(
        displayName = displayName,
        host = host,
        port = port,
        sharePath = sharePath,
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
                    label = { Text(stringResource(R.string.network_display_name_optional)) },
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
                    value = sharePath,
                    onValueChange = { sharePath = it },
                    label = { Text(stringResource(R.string.network_share_path)) },
                    supportingText = { Text(stringResource(R.string.network_share_path_hint)) },
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
                enabled = host.isNotBlank() && sharePath.isNotBlank(),
            ) {
                Text(stringResource(R.string.network_save))
            }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = { onTest(current(), password) },
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
