package com.hippo.ehviewer.ui.screen

import android.Manifest
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.MediaPermissions

/**
 * Access method when adding a library / folder source:
 * - SAF tree picker (full folder incl. archives)
 * - Device media via [READ_MEDIA_IMAGES] (Aves-style; images only)
 */
@Composable
fun LocalSourceAccessDialog(
    role: Int,
    onDismiss: () -> Unit,
    onChooseSaf: (role: Int) -> Unit,
    onChooseDeviceMedia: (role: Int) -> Unit,
) {
    val isLibrary = role == LIBRARY_ROOT_ROLE_LIBRARY
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isLibrary) {
                        R.string.library_add_library_source
                    } else {
                        R.string.library_add_folder_source
                    },
                ),
            )
        },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.source_access_saf)) },
                    supportingContent = { Text(stringResource(R.string.source_access_saf_summary)) },
                    leadingContent = {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        onDismiss()
                        onChooseSaf(role)
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.source_access_device_media)) },
                    supportingContent = { Text(stringResource(R.string.source_access_device_media_summary)) },
                    leadingContent = {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        onDismiss()
                        onChooseDeviceMedia(role)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/**
 * Permission launcher for device-media access. [onGranted] runs after permission is OK
 * (caller should call LocalLibrary.addMediaStoreRoot on IO).
 */
@Composable
fun rememberMediaPermissionLauncher(
    onGranted: (role: Int) -> Unit,
    onDenied: () -> Unit,
): MediaPermissionLauncher {
    val context = LocalContext.current
    var pendingRole by remember { mutableIntStateOf(LIBRARY_ROOT_ROLE_LIBRARY) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
            MediaPermissions.hasImageAccess(context)
        if (granted) {
            onGranted(pendingRole)
        } else {
            onDenied()
        }
    }
    return remember(launcher, onGranted, onDenied, context) {
        MediaPermissionLauncher(
            launcher = launcher,
            setPendingRole = { pendingRole = it },
            hasAccess = { MediaPermissions.hasImageAccess(context) },
            onAlreadyGranted = onGranted,
        )
    }
}

class MediaPermissionLauncher(
    private val launcher: ManagedActivityResultLauncher<Array<String>, Map<String, @JvmSuppressWildcards Boolean>>,
    private val setPendingRole: (Int) -> Unit,
    private val hasAccess: () -> Boolean,
    private val onAlreadyGranted: (role: Int) -> Unit,
) {
    fun request(role: Int) {
        setPendingRole(role)
        if (hasAccess()) {
            onAlreadyGranted(role)
        } else {
            launcher.launch(MediaPermissions.required)
        }
    }
}
