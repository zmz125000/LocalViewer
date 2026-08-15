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
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.buildLocalBrowseStack
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.WebDavBrowserScreenDestination
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
fun navToReader(path: String, info: BaseGalleryInfo? = null, page: Int = -1) = navToReader(ReaderScreenArgs.Archive(path, page = page, info = info))

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

context(_: DestinationsNavigator)
fun navToWebDavFolderReader(
    sourceId: Long,
    remoteDir: String,
    imageNames: List<String>,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
) = navToReader(ReaderScreenArgs.WebDavFolder(sourceId, remoteDir, imageNames, page, info))

context(_: DestinationsNavigator)
fun navToSmbStreamArchiveReader(
    sourceId: Long,
    remotePath: String,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
) = navToReader(ReaderScreenArgs.SmbStreamArchive(sourceId, remotePath, page, info))

context(_: DestinationsNavigator)
fun navToWebDavStreamArchiveReader(
    sourceId: Long,
    remotePath: String,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
) = navToReader(ReaderScreenArgs.WebDavStreamArchive(sourceId, remotePath, page, info))

context(nav: DestinationsNavigator)
private fun navToReader(args: ReaderScreenArgs) = nav.navigate(ReaderScreenDestination(args)) { launchSingleTop = true }

/**
 * Open content from History with an optional parent-directory back stack.
 *
 * When [Settings.alwaysExitToDir] is on, [pushParentDir] runs first (set
 * [com.hippo.ehviewer.library.BrowseSession] + navigate to Folder/SMB/WebDAV browser
 * with `fromHistory = true`) so system back from the reader lands on that directory.
 * When off, only [openContent] runs and back returns to History (or the prior stack).
 *
 * Use this for every History → reader path that can land on a parent dir; pure dir
 * opens use [openLocalBrowseDir] / [openSmbBrowseDir] / [openWebDavBrowseDir] instead.
 */
inline fun openFromHistoryWithBackStack(
    pushParentDir: () -> Unit,
    openContent: () -> Unit,
) {
    if (Settings.alwaysExitToDir.value) {
        pushParentDir()
    }
    openContent()
}

/**
 * Open a local browse directory (History / Library / Favourites pin).
 *
 * [Settings.alwaysExitToDir] on: full root→dir stack so system back walks parents.
 * Off: leaf frame only so back leaves the browser to History/Library.
 */
context(nav: DestinationsNavigator)
fun openLocalBrowseDir(
    rootId: Long,
    rootDisplayName: String,
    rootPath: Path,
    relativePath: String,
    preferMediaStore: Boolean = true,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
) {
    val full = buildLocalBrowseStack(
        rootId = rootId,
        rootDisplayName = rootDisplayName,
        rootPath = rootPath,
        relativePath = relativePath,
        preferMediaStore = preferMediaStore,
    )
    BrowseSession.localStack = if (Settings.alwaysExitToDir.value) full else listOf(full.last())
    nav.navigate(FolderBrowserScreenDestination(fromHistory = fromHistory, fromLibrary = fromLibrary)) {
        launchSingleTop = true
    }
}

/**
 * Open an SMB browse directory (History / Library / Favourites pin).
 * When [Settings.alwaysExitToDir] is off and opened from History/Library, first
 * system back leaves the browser instead of walking parent segments.
 */
context(nav: DestinationsNavigator)
fun openSmbBrowseDir(
    sourceId: Long,
    remoteDir: String,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val exitToDir = Settings.alwaysExitToDir.value
    val fromOrigin = fromHistory || fromLibrary
    BrowseSession.setSmbSegments(sourceId, segments)
    BrowseSession.setSmbPhotoGrid(sourceId, null)
    BrowseSession.setSmbExitToOrigin(sourceId, !exitToDir && fromOrigin)
    nav.navigate(
        SmbBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory,
            fromLibrary = fromLibrary,
        ),
    ) { launchSingleTop = true }
}

/** Open a WebDAV browse directory (History / Library / Favourites pin). */
context(nav: DestinationsNavigator)
fun openWebDavBrowseDir(
    sourceId: Long,
    remoteDir: String,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val exitToDir = Settings.alwaysExitToDir.value
    val fromOrigin = fromHistory || fromLibrary
    BrowseSession.setWebDavSegments(sourceId, segments)
    BrowseSession.setWebDavPhotoGrid(sourceId, null)
    BrowseSession.setWebDavExitToOrigin(sourceId, !exitToDir && fromOrigin)
    nav.navigate(
        WebDavBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory,
            fromLibrary = fromLibrary,
        ),
    ) { launchSingleTop = true }
}

/**
 * Open a local folder gallery as the photo-grid virtual folder (History / Library tap).
 *
 * Aligns with [Settings.alwaysExitToDir]:
 * - On: stack = parent frames + photo-grid frame → back lands on parent dir.
 * - Off: stack = photo-grid frame only → back leaves the browser (History/Library).
 */
context(nav: DestinationsNavigator)
fun openLocalFolderPhotoGrid(
    rootId: Long,
    rootDisplayName: String,
    rootPath: Path,
    relativePath: String,
    preferMediaStore: Boolean = true,
    title: String? = null,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
) {
    val galleryStack = buildLocalBrowseStack(
        rootId = rootId,
        rootDisplayName = rootDisplayName,
        rootPath = rootPath,
        relativePath = relativePath,
        preferMediaStore = preferMediaStore,
    )
    val galleryFrame = galleryStack.last().copy(
        photoGrid = true,
        title = title?.takeIf { it.isNotBlank() } ?: galleryStack.last().title,
    )
    BrowseSession.localStack = if (Settings.alwaysExitToDir.value) {
        val parentRel = parentRelativeOfFile(relativePath)
        val parentStack = buildLocalBrowseStack(
            rootId = rootId,
            rootDisplayName = rootDisplayName,
            rootPath = rootPath,
            relativePath = parentRel,
            preferMediaStore = preferMediaStore,
        )
        parentStack + galleryFrame
    } else {
        listOf(galleryFrame)
    }
    nav.navigate(FolderBrowserScreenDestination(fromHistory = fromHistory, fromLibrary = fromLibrary)) {
        launchSingleTop = true
    }
}

/**
 * Open an SMB folder gallery as photo-grid (History / Library tap).
 * Back aligns with [Settings.alwaysExitToDir] (parent dir vs leave browser).
 */
context(nav: DestinationsNavigator)
fun openSmbFolderPhotoGrid(
    sourceId: Long,
    remoteDir: String,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val exitToDir = Settings.alwaysExitToDir.value
    val fromOrigin = fromHistory || fromLibrary
    BrowseSession.setSmbSegments(sourceId, segments)
    BrowseSession.setSmbExitToOrigin(sourceId, false)
    BrowseSession.setSmbPhotoGrid(
        sourceId,
        remote,
        enteredFromParent = exitToDir && remote.isNotEmpty(),
        exitToOrigin = !exitToDir && fromOrigin,
    )
    nav.navigate(
        SmbBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory,
            fromLibrary = fromLibrary,
        ),
    ) { launchSingleTop = true }
}

/** Open a WebDAV folder gallery as photo-grid (History / Library tap). */
context(nav: DestinationsNavigator)
fun openWebDavFolderPhotoGrid(
    sourceId: Long,
    remoteDir: String,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val exitToDir = Settings.alwaysExitToDir.value
    val fromOrigin = fromHistory || fromLibrary
    BrowseSession.setWebDavSegments(sourceId, segments)
    BrowseSession.setWebDavExitToOrigin(sourceId, false)
    BrowseSession.setWebDavPhotoGrid(
        sourceId,
        remote,
        enteredFromParent = exitToDir && remote.isNotEmpty(),
        exitToOrigin = !exitToDir && fromOrigin,
    )
    nav.navigate(
        WebDavBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory,
            fromLibrary = fromLibrary,
        ),
    ) { launchSingleTop = true }
}

context(_: Context, _: DialogState)
suspend fun showRestartDialog() {
    awaitConfirmationOrCancel {
        Text(stringResource(R.string.settings_restart))
    }
    restartApplication()
}
