package com.hippo.ehviewer.ui.main

import android.content.Intent
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_FOLDER
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.AddRootResult
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.UsbVolume
import com.hippo.ehviewer.library.UsbVolumes
import com.hippo.ehviewer.library.matchUsbRoot
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch

/**
 * Snackbar when a removable volume is mounted, and the opener shared with the
 * Library favourites pin. An already granted tree reuses that root id.
 */
@Composable
fun UsbDriveHost(
    navigator: DestinationsNavigator,
    snackbarState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roots by produceState(emptyList<LibraryRootEntity>()) {
        LocalLibrary.rootsFlow().collect { value = it }
    }
    var pendingUuid by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uuid = pendingUuid
        pendingUuid = null
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val label = UsbVolumes.mounted.value
                .firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
                ?.label
                .orEmpty()
                .ifBlank { "USB" }
            val added = withIOContext {
                LocalLibrary.addRoot(
                    treeUri = uri.toString(),
                    displayName = label,
                    role = LIBRARY_ROOT_ROLE_FOLDER,
                    accessMode = LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE,
                )
            }
            val id = when (added) {
                is AddRootResult.Created -> added.id
                is AddRootResult.AlreadyExists -> added.id
                is AddRootResult.UpgradedToLibrary -> added.id
            }
            val root = withIOContext { LocalLibrary.loadRoot(id) } ?: return@launch
            val path = LocalLibrary.rootPath(root) ?: return@launch
            with(navigator) {
                openLocalBrowseDir(
                    rootId = root.id,
                    rootDisplayName = root.displayName,
                    rootPath = path,
                    relativePath = "",
                    preferMediaStore = root.prefersMediaStore,
                    fromLibrary = true,
                )
            }
        }
    }

    fun openVolume(volume: UsbVolume) {
        val root = matchUsbRoot(roots, volume.uuid)
        val path = root?.let { LocalLibrary.rootPath(it) }
        if (root != null && path != null) {
            with(navigator) {
                openLocalBrowseDir(
                    rootId = root.id,
                    rootDisplayName = root.displayName,
                    rootPath = path,
                    relativePath = "",
                    preferMediaStore = root.prefersMediaStore,
                    fromLibrary = true,
                )
            }
            return
        }
        val manager = context.getSystemService(StorageManager::class.java) ?: return
        val storage = manager.storageVolumes.firstOrNull {
            it.uuid.equals(volume.uuid, ignoreCase = true)
        } ?: return
        pendingUuid = volume.uuid
        val intent = storage.createOpenDocumentTreeIntent().apply {
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        picker.launch(intent)
    }

    val openVolumeNow = rememberUpdatedState(::openVolume)
    LaunchedEffect(Unit) {
        UsbVolumes.openRequests.collect { openVolumeNow.value(it) }
    }
    LaunchedEffect(Unit) {
        var seen: Set<String>? = null
        UsbVolumes.mounted.collect { volumes ->
            val ids = volumes.map { it.uuid }.toSet()
            val previous = seen
            seen = ids
            if (previous == null) return@collect
            for (volume in volumes) {
                if (volume.uuid in previous) continue
                val result = snackbarState.showSnackbar(
                    message = context.getString(R.string.usb_drive_connected, volume.label),
                    actionLabel = context.getString(R.string.usb_drive_open),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openVolumeNow.value(volume)
                }
            }
        }
    }
}
