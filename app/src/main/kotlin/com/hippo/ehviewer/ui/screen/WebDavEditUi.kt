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
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore

data class WebDavEditorState(
    val id: Long = 0,
    val displayName: String = "",
    val baseUrl: String = "",
    val pathPrefix: String = "",
    val username: String = "",
    val password: String = "",
)

fun WebDavEditorState.resolvedDisplayName(): String {
    val name = displayName.trim()
    if (name.isNotEmpty()) return name
    return runCatching {
        java.net.URI(baseUrl.trim().let { if (it.contains("://")) it else "https://$it" }).host
    }.getOrNull().orEmpty().ifBlank { baseUrl.trim() }
}

fun WebDavSourceEntity.toEditorState(includePassword: Boolean = true) = WebDavEditorState(
    id = id,
    displayName = displayName,
    baseUrl = baseUrl,
    pathPrefix = pathPrefix,
    username = username,
    password = if (includePassword) WebDavPasswordStore.get(id) else "",
)

fun WebDavSourceEntity.toDuplicateEditorState() = toEditorState(includePassword = true).copy(
    id = 0,
    displayName = "",
)

@Composable
fun WebDavEditDialog(
    state: WebDavEditorState,
    onDismiss: () -> Unit,
    onSave: (WebDavEditorState, password: String) -> Unit,
    onTest: (WebDavEditorState, password: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var displayName by remember(state.id) { mutableStateOf(state.displayName) }
    var baseUrl by remember(state.id) { mutableStateOf(state.baseUrl) }
    var pathPrefix by remember(state.id) { mutableStateOf(state.pathPrefix) }
    var username by remember(state.id) { mutableStateOf(state.username) }
    var password by remember(state.id) { mutableStateOf(state.password) }
    // Default off for new adds. For edit, only on if already blank credentials.
    var anonymous by remember(state.id) {
        mutableStateOf(
            state.id != 0L && state.username.isBlank() && state.password.isBlank(),
        )
    }
    val focusManager = LocalFocusManager.current
    val baseFocus = remember { FocusRequester() }
    val pathFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }

    fun current() = WebDavEditorState(
        id = state.id,
        displayName = displayName,
        baseUrl = baseUrl,
        pathPrefix = pathPrefix,
        username = if (anonymous) "" else username,
        password = if (anonymous) "" else password,
    )

    val canSave = baseUrl.trim().isNotEmpty()

    fun submit() {
        if (!canSave) return
        focusManager.clearFocus()
        val cur = current()
        onSave(cur, if (anonymous) "" else password)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (state.id == 0L) R.string.webdav_add else R.string.webdav_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.network_display_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { baseFocus.requestFocus() }),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.webdav_base_url)) },
                    placeholder = { Text(stringResource(R.string.webdav_base_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(baseFocus),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { pathFocus.requestFocus() }),
                    supportingText = if (WebDavClient.isExplicitHttp(baseUrl)) {
                        {
                            Text(
                                stringResource(R.string.webdav_http_hint),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = pathPrefix,
                    onValueChange = { pathPrefix = it },
                    label = { Text(stringResource(R.string.webdav_path_prefix)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(pathFocus),
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (anonymous) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { userFocus.requestFocus() },
                        onDone = { submit() },
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.webdav_anonymous),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = anonymous, onCheckedChange = { anonymous = it })
                }
                if (!anonymous) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.network_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(userFocus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.network_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().focusRequester(passFocus),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.network_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = {
                    val cur = current()
                    onTest(cur, if (anonymous) "" else password)
                },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.network_test))
            }
        },
    )
}
