package com.hippo.ehviewer.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.isAtLeastQ
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.observed
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.displayPath
import com.hippo.ehviewer.util.requestPermission
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar
import okio.Path.Companion.toOkioPath

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.DownloadScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_download)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(paddingValues)) {
            var downloadLocationState by ::downloadLocation.observed
            val cannotGetDownloadLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
            val selectDownloadDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                treeUri?.run {
                    launchIO {
                        contextOf<Context>().contentResolver.runCatching {
                            persistedUriPermissions.forEach {
                                releasePersistableUriPermission(it.uri, URI_FLAGS)
                            }
                            takePersistableUriPermission(treeUri, URI_FLAGS)
                            val path = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)).toOkioPath()
                            check(path.isDirectory) { "$path is not a directory" }
                            keepNoMediaFileStatus(path) // Check if the directory is writable
                            downloadLocationState = path
                        }.onFailure {
                            logcat(it)
                            launchSnackbar(cannotGetDownloadLocation)
                        }
                    }
                }
            }
            Preference(
                title = stringResource(id = R.string.settings_download_download_location),
                summary = downloadLocationState.toUri().displayPath,
            ) {
                launchIO {
                    val defaultDownloadDir = AppConfig.defaultDownloadDir
                    if (defaultDownloadDir?.delete() == false) {
                        val path = defaultDownloadDir.toOkioPath()
                        awaitConfirmationOrCancel(
                            confirmText = R.string.pick_new_download_location,
                            dismissText = if (downloadLocationState != path) {
                                R.string.reset_download_location
                            } else {
                                android.R.string.cancel
                            },
                            title = R.string.waring,
                            onCancelButtonClick = {
                                if (downloadLocationState != path) {
                                    contextOf<Context>().contentResolver.run {
                                        persistedUriPermissions.forEach {
                                            releasePersistableUriPermission(it.uri, URI_FLAGS)
                                        }
                                    }
                                    downloadLocationState = path
                                }
                            },
                        ) {
                            Text(stringResource(id = R.string.default_download_dir_not_empty))
                        }
                    }
                    try {
                        selectDownloadDirLauncher.launch(null)
                    } catch (_: ActivityNotFoundException) {
                        // Best effort for devices without DocumentsUI
                        if (!isAtLeastQ && requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            runCatching {
                                val path = Environment.getExternalStorageDirectory().toOkioPath() / AppConfig.APP_DIRNAME
                                path.mkdirs()
                                check(path.isDirectory) { "$path is not a directory" }
                                keepNoMediaFileStatus(path) // Check if the directory is writable
                                downloadLocationState = path
                                return@launchIO
                            }.onFailure {
                                logcat(it)
                            }
                        }
                        launchSnackbar(cannotGetDownloadLocation)
                    }
                }
            }
            val mediaScan = Settings.mediaScan.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_media_scan),
                summary = if (mediaScan.value) stringResource(id = R.string.settings_download_media_scan_summary_on) else stringResource(id = R.string.settings_download_media_scan_summary_off),
                state = mediaScan,
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_save_as_cbz),
                state = Settings.saveAsCbz.asMutableState(),
            )
        }
    }
}
