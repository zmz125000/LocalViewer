package com.hippo.ehviewer.ui.screen

import android.Manifest
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.MediaPermissions

/**
 * Access method when adding a library / folder source:
 * - SAF tree picker (full folder incl. archives)
 * - Device media via [READ_MEDIA_IMAGES] (Aves-style; images only)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSourceAccessDialog(
    role: Int,
    onDismiss: () -> Unit,
    onChooseSaf: (role: Int) -> Unit,
    onChooseDeviceMedia: (role: Int) -> Unit,
) {
    val isLibrary = role == LIBRARY_ROOT_ROLE_LIBRARY
    // Custom layout: one padding column so title aligns with option cards (default
    // AlertDialog title/text slots use different horizontal insets).
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isLibrary) {
                            R.string.library_add_library_source
                        } else {
                            R.string.library_add_folder_source
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    SourceAccessOption(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(R.string.source_access_saf),
                        summary = stringResource(R.string.source_access_saf_summary),
                        onClick = {
                            onDismiss()
                            onChooseSaf(role)
                        },
                    )
                    SourceAccessOption(
                        icon = Icons.Default.PhotoLibrary,
                        title = stringResource(R.string.source_access_device_media),
                        summary = stringResource(R.string.source_access_device_media_summary),
                        onClick = {
                            onDismiss()
                            onChooseDeviceMedia(role)
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceAccessOption(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        // Slightly different from dialog surface so options read as cards.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(7.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
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
