package com.hippo.ehviewer.ui.settings

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.ehviewer.ui.main.BrowseSaveAs
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.hippo.ehviewer.ui.tools.observed
import com.hippo.ehviewer.util.displayPath
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar
import okio.Path

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

private fun Path.treeUri(): Uri? {
    val uri = toUri()
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val authority = uri.authority ?: return null
    return runCatching {
        DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.DownloadScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_download)) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            var downloadLocationState by ::downloadLocation.observed
            val cannotGetDownloadLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
            val conversionLocation = Settings.imageConversionLocation.asMutableState()
            val askWhereToSave = Settings.browseAskWhereToSave.asMutableState()
            val askForSaveDestination = Settings.askWhereToSave.asMutableState()
            fun saveDirectory(selectedUri: Uri, previous: Uri?, save: (Path) -> Unit) {
                launchIO {
                    val ctx = contextOf<Context>()
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(selectedUri, URI_FLAGS)
                        val path = DocumentsContract.buildDocumentUriUsingTree(
                            selectedUri,
                            DocumentsContract.getTreeDocumentId(selectedUri),
                        ).toOkioPath()
                        check(path.isDirectory) { "$path is not a directory" }
                        keepNoMediaFileStatus(path)
                        previous?.takeIf { it != selectedUri }?.let {
                            runCatching { ctx.contentResolver.releasePersistableUriPermission(it, URI_FLAGS) }
                        }
                        save(path)
                    }.onFailure {
                        logcat(it)
                        launchSnackbar(cannotGetDownloadLocation)
                    }
                }
            }
            val selectDownloadDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                treeUri?.let { selectedUri ->
                    saveDirectory(selectedUri, downloadLocationState.treeUri()) { downloadLocationState = it }
                }
            }
            val selectConversionDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                treeUri?.let { selectedUri ->
                    saveDirectory(selectedUri, conversionLocation.value?.let(Uri::parse)) {
                        conversionLocation.value = selectedUri.toString()
                    }
                }
            }
            Preference(
                title = stringResource(id = R.string.settings_download_download_location),
                summary = downloadLocationState.toUri().displayPath,
            ) {
                try {
                    selectDownloadDirLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    launchSnackbar(cannotGetDownloadLocation)
                }
            }
            me.zhanghai.compose.preference.SwitchPreference(
                value = askForSaveDestination.value,
                onValueChange = { ask ->
                    launchIO {
                        with(contextOf<Context>()) { BrowseSaveAs.updateAskForSaveDestination(ask) }
                    }
                },
                title = { Text(stringResource(R.string.ask_where_to_save)) },
            )
            val conversionLocationSummary = conversionLocation.value?.let { saved ->
                runCatching {
                    val treeUri = Uri.parse(saved)
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    ).toOkioPath().toUri().displayPath
                }.getOrNull()
            } ?: stringResource(R.string.image_conversion_default_location_not_set)
            Preference(
                title = stringResource(R.string.image_conversion_default_location),
                summary = conversionLocationSummary,
            ) {
                try {
                    selectConversionDirLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    launchSnackbar(cannotGetDownloadLocation)
                }
            }
            me.zhanghai.compose.preference.SwitchPreference(
                value = askWhereToSave.value,
                onValueChange = { ask ->
                    launchIO {
                        with(contextOf<Context>()) { BrowseSaveAs.updateAskWhereToSave(ask) }
                    }
                },
                title = { Text(stringResource(R.string.browse_ask_where_to_save)) },
            )
            val mediaScan = Settings.mediaScan.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_media_scan),
                summary = if (mediaScan.value) stringResource(id = R.string.settings_download_media_scan_summary_on) else stringResource(id = R.string.settings_download_media_scan_summary_off),
                state = mediaScan,
            )
            ImageConversionPreferences()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_save_as_cbz),
                state = Settings.saveAsCbz.asMutableState(),
            )
        }
    }
}
