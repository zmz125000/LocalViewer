package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.write
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.util.restartApplication
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path

private fun removeNoMediaFile(downloadDir: Path) {
    (downloadDir / ".nomedia").delete()
}

private fun ensureNoMediaFile(downloadDir: Path) {
    (downloadDir / ".nomedia").apply { if (!exists()) write {} }
}

private val lck = Mutex()

suspend fun keepNoMediaFileStatus(
    downloadDir: Path = downloadLocation,
    mediaScan: Boolean = Settings.mediaScan.value,
) {
    if (downloadDir.isDirectory) {
        lck.withLock {
            if (mediaScan) {
                removeNoMediaFile(downloadDir)
            } else {
                ensureNoMediaFile(downloadDir)
            }
        }
    }
}

context(_: DestinationsNavigator)
fun navToReader(path: String) = navToReader(ReaderScreenArgs.Archive(path))

context(_: DestinationsNavigator)
fun navToLocalFolderReader(path: String, info: BaseGalleryInfo? = null, page: Int = -1) = navToReader(ReaderScreenArgs.LocalFolder(path, page, info))

context(_: DestinationsNavigator)
fun navToSmbFolderReader(
    sourceId: Long,
    remoteDir: String,
    imageNames: List<String>,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
) = navToReader(ReaderScreenArgs.SmbFolder(sourceId, remoteDir, imageNames, page, info))

context(nav: DestinationsNavigator)
private fun navToReader(args: ReaderScreenArgs) = nav.navigate(ReaderScreenDestination(args)) { launchSingleTop = true }

context(_: Context, _: DialogState)
suspend fun showRestartDialog() {
    awaitConfirmationOrCancel {
        Text(stringResource(R.string.settings_restart))
    }
    restartApplication()
}
