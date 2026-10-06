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
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.NavTabWindows
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
import splitties.init.appCtx

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
fun navToReader(path: String, info: BaseGalleryInfo? = null, page: Int = -1, skipPdfPrimary: Boolean = false) = navToReader(ReaderScreenArgs.Archive(path, page = page, info = info, skipPdfPrimary = skipPdfPrimary))

context(_: DestinationsNavigator)
fun navToLocalFolderReader(
    path: String,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
    imageNames: List<String> = emptyList(),
) = navToReader(ReaderScreenArgs.LocalFolder(path, page, info, imageNames))

context(_: DestinationsNavigator)
fun navToLocalImageListReader(
    page: Int,
    title: String = "",
) = navToReader(ReaderScreenArgs.LocalImageList(page, title))

context(_: DestinationsNavigator)
fun navToLocalZipFolderReader(
    zipPath: String,
    innerRel: String,
    imageNames: List<String>,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
) = navToReader(ReaderScreenArgs.LocalZipFolder(zipPath, innerRel, imageNames, page, info))

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
    skipPdfPrimary: Boolean = false,
) = navToReader(ReaderScreenArgs.SmbStreamArchive(sourceId, remotePath, page, info, skipPdfPrimary))

context(_: DestinationsNavigator)
fun navToWebDavStreamArchiveReader(
    sourceId: Long,
    remotePath: String,
    info: BaseGalleryInfo? = null,
    page: Int = -1,
    skipPdfPrimary: Boolean = false,
) = navToReader(ReaderScreenArgs.WebDavStreamArchive(sourceId, remotePath, page, info, skipPdfPrimary))

context(nav: DestinationsNavigator)
private fun navToReader(args: ReaderScreenArgs) {
    if (OpenPdfBySettings.shouldRedirect(args)) {
        OpenPdfBySettings.launch(appCtx, args)
        return
    }
    nav.navigate(ReaderScreenDestination(args)) { launchSingleTop = true }
}

/**
 * Whether folder / photo-grid back should walk parent directories for this open.
 *
 * - [Settings.alwaysExitToDir] on → always walk parents (History, Library, Fav, side panel).
 * - Off + [sidePanelOpen] → [Settings.sidePanelDirBackToUpper] only (ignores the nav tab).
 * - Off + [fromHistory] + [Settings.historyDirBackToUpper] on → History folders only.
 * - Otherwise leaf / exit to origin list.
 */
fun walkUpperDirsForBrowseOpen(
    fromHistory: Boolean,
    fromLibrary: Boolean = false,
    sidePanelOpen: Boolean = false,
): Boolean {
    if (Settings.alwaysExitToDir.value) return true
    if (sidePanelOpen) return Settings.sidePanelDirBackToUpper.value
    if (fromHistory && Settings.historyDirBackToUpper.value) return true
    return false
}

/** First back leaves the browser when this open is a leaf pin from a list or the side panel. */
private fun leaveBrowserOnBack(
    fromHistory: Boolean,
    fromLibrary: Boolean,
    fromSidePanel: Boolean,
    sidePanelOpen: Boolean,
    walkParents: Boolean,
): Boolean {
    if (walkParents) return false
    if (sidePanelOpen) return true
    return (fromHistory || fromLibrary) && !fromSidePanel
}

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
 * Full root→dir stack when [walkUpperDirsForBrowseOpen]; else leaf only.
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
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
) {
    val full = buildLocalBrowseStack(
        rootId = rootId,
        rootDisplayName = rootDisplayName,
        rootPath = rootPath,
        relativePath = relativePath,
        preferMediaStore = preferMediaStore,
    )
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    ExplorerWindows.prepareSpawn()
    BrowseSession.localStack = if (walkParents) full else listOf(full.last())
    ExplorerWindows.finishLocalSpawn(rootDisplayName, fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(FolderBrowserScreenDestination(fromHistory = fromHistory && !fromSidePanel, fromLibrary = fromLibrary && !fromSidePanel)) {
        launchSingleTop = true
    }
}

/**
 * Open an SMB browse directory (History / Library / Favourites pin).
 * When not walking parents and opened from History/Library, first back leaves the browser.
 */
context(nav: DestinationsNavigator)
fun openSmbBrowseDir(
    sourceId: Long,
    remoteDir: String,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    val leaveOnBack = leaveBrowserOnBack(fromHistory, fromLibrary, fromSidePanel, sidePanelOpen, walkParents)
    ExplorerWindows.prepareSpawn()
    BrowseSession.setSmbSegments(sourceId, segments)
    BrowseSession.setSmbPhotoGrid(sourceId, null)
    BrowseSession.setSmbExitToOrigin(sourceId, leaveOnBack)
    ExplorerWindows.finishSmbSpawn(sourceId, "", fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(
        SmbBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory && !fromSidePanel,
            fromLibrary = fromLibrary && !fromSidePanel,
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
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    val leaveOnBack = leaveBrowserOnBack(fromHistory, fromLibrary, fromSidePanel, sidePanelOpen, walkParents)
    ExplorerWindows.prepareSpawn()
    BrowseSession.setWebDavSegments(sourceId, segments)
    BrowseSession.setWebDavPhotoGrid(sourceId, null)
    BrowseSession.setWebDavExitToOrigin(sourceId, leaveOnBack)
    ExplorerWindows.finishWebDavSpawn(sourceId, "", fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(
        WebDavBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory && !fromSidePanel,
            fromLibrary = fromLibrary && !fromSidePanel,
        ),
    ) { launchSingleTop = true }
}

/**
 * Open a local folder gallery as the photo-grid virtual folder (History / Library tap).
 * When walking parents: parent frames + photo-grid frame → back lands on parent dir.
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
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
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
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    ExplorerWindows.prepareSpawn()
    BrowseSession.localStack = if (walkParents) {
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
    ExplorerWindows.finishLocalSpawn(rootDisplayName, fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(FolderBrowserScreenDestination(fromHistory = fromHistory && !fromSidePanel, fromLibrary = fromLibrary && !fromSidePanel)) {
        launchSingleTop = true
    }
}

/**
 * Open a local video folder as a Video-filter overlay (Library tap).
 * Same stack idea as [openLocalFolderPhotoGrid]: does not write global content mode.
 */
context(nav: DestinationsNavigator)
fun openLocalVideoFolder(
    rootId: Long,
    rootDisplayName: String,
    rootPath: Path,
    relativePath: String,
    preferMediaStore: Boolean = true,
    title: String? = null,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
) {
    val folderStack = buildLocalBrowseStack(
        rootId = rootId,
        rootDisplayName = rootDisplayName,
        rootPath = rootPath,
        relativePath = relativePath,
        preferMediaStore = preferMediaStore,
    )
    val overlayFrame = folderStack.last().copy(
        videoFolder = true,
        title = title?.takeIf { it.isNotBlank() } ?: folderStack.last().title,
    )
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    ExplorerWindows.prepareSpawn()
    BrowseSession.localStack = if (walkParents) {
        val parentRel = parentRelativeOfFile(relativePath)
        val parentStack = buildLocalBrowseStack(
            rootId = rootId,
            rootDisplayName = rootDisplayName,
            rootPath = rootPath,
            relativePath = parentRel,
            preferMediaStore = preferMediaStore,
        )
        parentStack + overlayFrame
    } else {
        listOf(overlayFrame)
    }
    ExplorerWindows.finishLocalSpawn(rootDisplayName, fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(FolderBrowserScreenDestination(fromHistory = fromHistory && !fromSidePanel, fromLibrary = fromLibrary && !fromSidePanel)) {
        launchSingleTop = true
    }
}

/**
 * Open an SMB folder gallery as photo-grid (History / Library tap).
 */
context(nav: DestinationsNavigator)
fun openSmbFolderPhotoGrid(
    sourceId: Long,
    remoteDir: String,
    fromHistory: Boolean = false,
    fromLibrary: Boolean = false,
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    val leaveOnBack = leaveBrowserOnBack(fromHistory, fromLibrary, fromSidePanel, sidePanelOpen, walkParents)
    ExplorerWindows.prepareSpawn()
    BrowseSession.setSmbSegments(sourceId, segments)
    BrowseSession.setSmbExitToOrigin(sourceId, false)
    BrowseSession.setSmbPhotoGrid(
        sourceId,
        remote,
        enteredFromParent = walkParents && remote.isNotEmpty(),
        exitToOrigin = leaveOnBack,
    )
    ExplorerWindows.finishSmbSpawn(sourceId, "", fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(
        SmbBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory && !fromSidePanel,
            fromLibrary = fromLibrary && !fromSidePanel,
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
    fromSidePanel: Boolean = false,
    sidePanelOpen: Boolean = false,
) {
    val remote = remoteDir.trim('/').let { if (it == ".") "" else it }
    val segments = remote.split('/').filter { it.isNotEmpty() }
    val walkParents = walkUpperDirsForBrowseOpen(fromHistory, fromLibrary, sidePanelOpen)
    val leaveOnBack = leaveBrowserOnBack(fromHistory, fromLibrary, fromSidePanel, sidePanelOpen, walkParents)
    ExplorerWindows.prepareSpawn()
    BrowseSession.setWebDavSegments(sourceId, segments)
    BrowseSession.setWebDavExitToOrigin(sourceId, false)
    BrowseSession.setWebDavPhotoGrid(
        sourceId,
        remote,
        enteredFromParent = walkParents && remote.isNotEmpty(),
        exitToOrigin = leaveOnBack,
    )
    ExplorerWindows.finishWebDavSpawn(sourceId, "", fromHistory, fromLibrary, fromSidePanel)
    rememberNavTabWindow(fromHistory, fromLibrary, fromSidePanel)
    nav.navigate(
        WebDavBrowserScreenDestination(
            sourceId = sourceId,
            initialRelativePath = remote,
            fromHistory = fromHistory && !fromSidePanel,
            fromLibrary = fromLibrary && !fromSidePanel,
        ),
    ) { launchSingleTop = true }
}

private fun rememberNavTabWindow(fromHistory: Boolean, fromLibrary: Boolean, fromSidePanel: Boolean) {
    if (fromSidePanel) return
    val id = ExplorerWindows.activeId ?: return
    when {
        fromHistory -> NavTabWindows.remember(NavTabWindows.Tab.History, id)
        fromLibrary -> NavTabWindows.remember(NavTabWindows.Tab.Library, id)
    }
}

context(_: Context, _: DialogState)
suspend fun showRestartDialog() {
    awaitConfirmationOrCancel {
        Text(stringResource(R.string.settings_restart))
    }
    restartApplication()
}
