package com.hippo.ehviewer.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.AddRootResult
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.MediaPermissions
import com.hippo.ehviewer.library.displayNameForTreeUri
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.LocalNetworkPermission
import com.hippo.ehviewer.util.ensureLocalNetworkPermission
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import moe.tarsin.snackbar
import moe.tarsin.string

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

/** Owns the launchers and editors used by the Library empty-state add action. */
@Composable
context(_: MainActivity, _: SnackbarHostState, _: CoroutineScope)
fun LibraryAddMediaSourceFlow(showDialog: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val alreadyAdded = stringResource(R.string.library_root_already_added)
    val cannotGetLocation = stringResource(R.string.settings_download_cant_get_download_location)
    val permissionDenied = stringResource(R.string.source_media_permission_denied)
    val deviceMediaName = stringResource(R.string.source_device_media_name)
    var pendingSafAccessMode by remember { mutableIntStateOf(LIBRARY_ROOT_ACCESS_MEDIA) }
    var openSafAfterMediaPerm by remember { mutableStateOf(false) }
    var mediaDenied by remember { mutableStateOf(false) }
    var smbEditor by remember { mutableStateOf<SmbEditorState?>(null) }
    var webDavEditor by remember { mutableStateOf<WebDavEditorState?>(null) }

    LaunchedEffect(mediaDenied) {
        if (mediaDenied) {
            try {
                snackbar(permissionDenied)
            } finally {
                mediaDenied = false
            }
        }
    }

    fun addDeviceMedia() {
        launchIO {
            when (LocalLibrary.addMediaStoreRoot(deviceMediaName, LIBRARY_ROOT_ROLE_LIBRARY)) {
                is AddRootResult.Created, is AddRootResult.UpgradedToLibrary -> Unit
                is AddRootResult.AlreadyExists -> launch { snackbar(alreadyAdded) }
            }
        }
    }

    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        val accessMode = pendingSafAccessMode
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
                when (LocalLibrary.addRoot(treeUri.toString(), name, LIBRARY_ROOT_ROLE_LIBRARY, accessMode)) {
                    is AddRootResult.Created, is AddRootResult.UpgradedToLibrary -> Unit
                    is AddRootResult.AlreadyExists -> launch { snackbar(alreadyAdded) }
                }
            }.onFailure {
                logcat(it)
                launch { snackbar(cannotGetLocation) }
            }
        }
    }

    fun openSafPicker() {
        try {
            pickRoot.launch(null)
        } catch (_: ActivityNotFoundException) {
            launch { snackbar(string(R.string.error_cant_find_activity)) }
        }
    }

    val mediaPermission = rememberMediaPermissionLauncher(
        onGranted = {
            if (openSafAfterMediaPerm) {
                openSafAfterMediaPerm = false
                openSafPicker()
            } else {
                addDeviceMedia()
            }
        },
        onDenied = {
            if (openSafAfterMediaPerm) {
                openSafAfterMediaPerm = false
                openSafPicker()
            } else {
                mediaDenied = true
            }
        },
    )

    fun launchFolderPicker(accessMode: Int) {
        pendingSafAccessMode = accessMode
        if (accessMode != LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE &&
            MediaPermissions.shouldRequestMediaPermissionForSafAdd(context)
        ) {
            openSafAfterMediaPerm = true
            mediaPermission.request(LIBRARY_ROOT_ROLE_LIBRARY)
        } else {
            openSafPicker()
        }
    }

    if (showDialog) {
        AddMediaSourceDialog(
            onDismiss = onDismiss,
            onChooseSmb = { smbEditor = SmbEditorState() },
            onChooseWebDav = { webDavEditor = WebDavEditorState() },
            onChooseDeviceMedia = { mediaPermission.request(LIBRARY_ROOT_ROLE_LIBRARY) },
            onChooseFolderMedia = { launchFolderPicker(LIBRARY_ROOT_ACCESS_MEDIA) },
            onChooseFolderFiles = { launchFolderPicker(LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE) },
        )
    }

    smbEditor?.let { state ->
        SmbEditDialog(
            state = state,
            onDismiss = { smbEditor = null },
            onSave = { saved, password ->
                val (share, pathPrefix) = saved.resolvedShareAndPath()
                launchIO {
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
                }
                smbEditor = null
            },
            onDelete = { smbEditor = null },
            onTest = { testState, password, messages -> testSmbSource(testState, password, messages) },
        )
    }

    webDavEditor?.let { state ->
        WebDavEditDialog(
            state = state,
            onDismiss = { webDavEditor = null },
            onSave = { saved, password ->
                launchIO {
                    WebDavRepository.add(
                        displayName = saved.resolvedDisplayName(),
                        baseUrl = saved.baseUrl,
                        pathPrefix = saved.pathPrefix,
                        username = saved.username,
                        password = password,
                    )
                }
                webDavEditor = null
            },
            onTest = { testState, password, messages -> testWebDavSource(testState, password, messages) },
            onDelete = null,
        )
    }
}

context(_: MainActivity, _: CoroutineScope)
private fun testSmbSource(state: SmbEditorState, password: String, messages: SnackbarHostState) {
    val (share, pathPrefix) = state.resolvedShareAndPath()
    launch {
        if (!ensureLocalNetworkPermission()) {
            messages.showSnackbar(string(R.string.network_test_fail, LocalNetworkPermission.deniedMessage()))
            return@launch
        }
        val entity = SmbSourceEntity(
            id = state.id, displayName = state.resolvedDisplayName(), host = state.host.trim(),
            port = state.port.toIntOrNull() ?: 445, share = share, pathPrefix = pathPrefix,
            username = state.username, domain = state.domain, addedAt = 0L,
        )
        val result = SmbGateway.testConnection(entity, password)
        if (result.isSuccess) {
            messages.showSnackbar(string(R.string.network_test_ok))
        } else {
            messages.showSnackbar(string(R.string.network_test_fail, result.exceptionOrNull()?.message ?: "error"))
        }
    }
}

context(_: MainActivity, _: CoroutineScope)
private fun testWebDavSource(state: WebDavEditorState, password: String, messages: SnackbarHostState) {
    launch {
        if (!ensureLocalNetworkPermission()) {
            messages.showSnackbar(string(R.string.network_test_fail, LocalNetworkPermission.deniedMessage()))
            return@launch
        }
        val entity = WebDavSourceEntity(
            id = state.id,
            displayName = state.resolvedDisplayName(),
            baseUrl = state.baseUrl.trim(),
            pathPrefix = state.pathPrefix,
            username = state.username,
            addedAt = 0L,
        )
        val result = WebDavClient.testConnection(entity, password)
        if (result.isSuccess) {
            messages.showSnackbar(string(R.string.network_test_ok))
        } else {
            messages.showSnackbar(string(R.string.network_test_fail, result.exceptionOrNull()?.message ?: "error"))
        }
    }
}
