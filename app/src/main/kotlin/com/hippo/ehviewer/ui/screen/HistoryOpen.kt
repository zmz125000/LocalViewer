package com.hippo.ehviewer.ui.screen

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FOLDER
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN
import com.hippo.ehviewer.library.LocalFolderListing
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.SMB_FOLDER_TOKEN
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_FOLDER_TOKEN
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.buildLocalBrowseStack
import com.hippo.ehviewer.library.isEbookFileName
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isVideoFileName
import com.hippo.ehviewer.library.libraryBrowseRelative
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.library.resolveRelative
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.library.toBaseGalleryInfo
import com.hippo.ehviewer.library.withLocalZipCentralDirectory
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.OpenPdfExternally
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToLocalZipFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.navToSmbFolderReader
import com.hippo.ehviewer.ui.navToSmbStreamArchiveReader
import com.hippo.ehviewer.ui.navToWebDavFolderReader
import com.hippo.ehviewer.ui.navToWebDavStreamArchiveReader
import com.hippo.ehviewer.ui.openFromHistoryWithBackStack
import com.hippo.ehviewer.ui.openLocalBrowseDir
import com.hippo.ehviewer.ui.openLocalFolderPhotoGrid
import com.hippo.ehviewer.ui.openSmbBrowseDir
import com.hippo.ehviewer.ui.openSmbFolderPhotoGrid
import com.hippo.ehviewer.ui.openWebDavBrowseDir
import com.hippo.ehviewer.ui.openWebDavFolderPhotoGrid
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CoroutineScope
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path.Companion.toPath

/**
 * Open one history row the same way History does.
 * [filterQuery] records a device search when the list was filtered.
 */
context(_: DestinationsNavigator, _: CoroutineScope, _: SnackbarHostState, _: MainActivity)
fun openHistoryRecord(context: Context, info: GalleryEntity, filterQuery: String = "") {
    if (filterQuery.isNotEmpty()) {
        launch { withIOContext { recordDeviceSearchHistory(filterQuery) } }
    }
    launch {
        when (val target = LocalHistory.parse(info)) {
            is LocalHistoryTarget.LibraryGallery -> {
                val local = withIOContext { LocalLibrary.loadGallery(target.galleryId) }
                if (local == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                if (local.kind == LOCAL_GALLERY_KIND_VIDEO_FILE) {
                    val path = local.contentPath
                    val name = local.title
                    val mime = mimeTypeForFileName(name)
                    val inApp = libraryVideoFileUsesMedia3(
                        media3 = Settings.useMedia3Player.value,
                        media3ForLibrary = Settings.useMedia3PlayerForLibrary.value,
                        videoMode = LibraryVideoMode.Files,
                    )
                    if (inApp) {
                        OpenFileExternally.playLocal(context, path, displayName = name, mimeType = mime)
                    } else {
                        OpenFileExternally.openLocal(context, path, displayName = name, mimeType = mime)
                    }
                    return@launch
                }
                if (local.kind == LOCAL_GALLERY_KIND_VIDEO_FOLDER) {
                    val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                    val rootPath = root?.let { LocalLibrary.rootPath(it) }
                    if (root == null || rootPath == null) {
                        snackbar(string(R.string.history_unavailable))
                        withIOContext { EhDB.deleteHistoryInfo(info) }
                        return@launch
                    }
                    openLocalBrowseDir(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = libraryBrowseRelative(local.relativePath),
                        preferMediaStore = root.prefersMediaStore,
                        fromHistory = true,
                    )
                    return@launch
                }
                if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                    navToReader(local.contentPath)
                } else {
                    val zipOpen = ZipPaths.parseGallery(local.contentPath)
                    if (zipOpen != null) {
                        val (zipAbs, inner) = zipOpen
                        val names = withIOContext {
                            withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                                ZipAsDirListing.directImageNames(cd, inner)
                            }.orEmpty()
                        }
                        if (names.isEmpty()) {
                            snackbar(string(R.string.browse_open_failed))
                            return@launch
                        }
                        val gi = local.toBaseGalleryInfo()
                        val parentRel = ZipAsDirListing.parentBrowseRelative(local.relativePath)
                        val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                        val rootPath = root?.let { LocalLibrary.rootPath(it) }
                        openFromHistoryWithBackStack(
                            pushParentDir = {
                                if (root != null && rootPath != null) {
                                    openLocalBrowseDir(
                                        rootId = root.id,
                                        rootDisplayName = root.displayName,
                                        rootPath = rootPath,
                                        relativePath = parentRel,
                                        preferMediaStore = root.prefersMediaStore,
                                        fromHistory = true,
                                    )
                                }
                            },
                            openContent = {
                                navToLocalZipFolderReader(zipAbs, inner, names, gi)
                            },
                        )
                        return@launch
                    }
                    if (Settings.photoGridMode.value) {
                        val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                        val rootPath = root?.let { LocalLibrary.rootPath(it) }
                        if (root == null || rootPath == null) {
                            navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                        } else {
                            withIOContext { EhDB.putHistoryInfo(info) }
                            openLocalFolderPhotoGrid(
                                rootId = root.id,
                                rootDisplayName = root.displayName,
                                rootPath = rootPath,
                                relativePath = local.relativePath,
                                preferMediaStore = root.prefersMediaStore,
                                title = local.title,
                                fromHistory = true,
                            )
                        }
                    } else {
                        navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                    }
                }
            }
            is LocalHistoryTarget.LocalBrowseFolder -> {
                val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                val rootPath = root?.let { LocalLibrary.rootPath(it) }
                if (root == null || rootPath == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                // Re-open → bump this dir pin to the top of Directories.
                withIOContext {
                    LocalHistory.recordLocalBrowseFolder(
                        rootId = root.id,
                        relativePath = target.relativePath,
                        title = info.title ?: root.displayName,
                        thumbKey = info.thumbKey,
                    )
                }
                openLocalBrowseDir(
                    rootId = root.id,
                    rootDisplayName = root.displayName,
                    rootPath = rootPath,
                    relativePath = target.relativePath,
                    preferMediaStore = root.prefersMediaStore,
                    fromHistory = true,
                )
            }
            is LocalHistoryTarget.SmbBrowseFolder -> {
                val source = withIOContext { SmbRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                withIOContext {
                    LocalHistory.recordSmbBrowseFolder(
                        sourceId = source.id,
                        relativePath = target.relativePath,
                        title = info.title ?: source.displayName,
                        thumbKey = info.thumbKey,
                    )
                }
                openSmbBrowseDir(
                    sourceId = source.id,
                    remoteDir = target.relativePath,
                    fromHistory = true,
                )
            }
            is LocalHistoryTarget.WebDavBrowseFolder -> {
                val source = withIOContext { WebDavRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                withIOContext {
                    LocalHistory.recordWebDavBrowseFolder(
                        sourceId = source.id,
                        relativePath = target.relativePath,
                        title = info.title ?: source.displayName,
                        thumbKey = info.thumbKey,
                    )
                }
                openWebDavBrowseDir(
                    sourceId = source.id,
                    remoteDir = target.relativePath,
                    fromHistory = true,
                )
            }
            is LocalHistoryTarget.LocalFolderGallery -> {
                // Photo-grid mode: tap opens image grid. Else reader (+ optional parent stack).
                val root = withIOContext { LocalLibrary.loadRoot(target.rootId) }
                val rootPath = root?.let { LocalLibrary.rootPath(it) }
                if (root == null || rootPath == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val zipGallery = ZipAsDirListing.recoverZipGalleryRelative(
                    rootAbsolutePath = rootPath.toString(),
                    relativePath = target.relativePath,
                    coverPath = info.thumbKey,
                )
                if (zipGallery != null) {
                    val (zipRel, inner) = zipGallery
                    val zipAbs = rootPath.resolveRelative(zipRel).toString()
                    val galleryDir = ZipAsDirListing.virtualRelativeDir(zipRel, inner)
                    val names = withIOContext {
                        FolderGalleryIndex.loadLocal(
                            target.rootId,
                            LocalFolderListing.rootConfigKey(
                                rootPath,
                                root.prefersMediaStore,
                            ),
                            galleryDir,
                        ) ?: withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                            ZipAsDirListing.directImageNames(cd, inner)
                        }.orEmpty()
                    }
                    val gi = BaseGalleryInfo(
                        gid = info.gid,
                        token = LOCAL_FOLDER_TOKEN,
                        title = info.title ?: zipRel.substringAfterLast('/'),
                        pages = names.size,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = info.thumbKey,
                        uploader = "${target.rootId}\u0000${target.relativePath.trim('/')}",
                        category = 0,
                    )
                    val parentRel = ZipAsDirListing.parentBrowseRelative(target.relativePath)
                    openFromHistoryWithBackStack(
                        pushParentDir = {
                            openLocalBrowseDir(
                                rootId = root.id,
                                rootDisplayName = root.displayName,
                                rootPath = rootPath,
                                relativePath = parentRel,
                                preferMediaStore = root.prefersMediaStore,
                                fromHistory = true,
                            )
                        },
                        openContent = {
                            navToLocalZipFolderReader(zipAbs, inner, names, gi)
                        },
                    )
                    return@launch
                }
                val fullStack = buildLocalBrowseStack(
                    rootId = root.id,
                    rootDisplayName = root.displayName,
                    rootPath = rootPath,
                    relativePath = target.relativePath,
                    preferMediaStore = root.prefersMediaStore,
                )
                val galleryPath = fullStack.last().path
                val parentRel = parentRelativeOfFile(target.relativePath)
                // Same identity as browse openFolderGallery so progress stubs keep path.
                val rel = target.relativePath.trim('/')
                val title = info.title ?: rel.substringAfterLast('/').ifEmpty { "Folder" }
                if (Settings.photoGridMode.value) {
                    withIOContext { EhDB.putHistoryInfo(info) }
                    openLocalFolderPhotoGrid(
                        rootId = root.id,
                        rootDisplayName = root.displayName,
                        rootPath = rootPath,
                        relativePath = target.relativePath,
                        preferMediaStore = root.prefersMediaStore,
                        title = title,
                        fromHistory = true,
                    )
                    return@launch
                }
                val gi = BaseGalleryInfo(
                    gid = info.gid,
                    token = LOCAL_FOLDER_TOKEN,
                    title = title,
                    pages = info.pages,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = info.thumbKey,
                    uploader = "${target.rootId}\u0000$rel",
                    category = 0,
                )
                openFromHistoryWithBackStack(
                    pushParentDir = {
                        openLocalBrowseDir(
                            rootId = root.id,
                            rootDisplayName = root.displayName,
                            rootPath = rootPath,
                            relativePath = parentRel,
                            preferMediaStore = root.prefersMediaStore,
                            fromHistory = true,
                        )
                    },
                    openContent = { navToLocalFolderReader(galleryPath, gi) },
                )
            }
            is LocalHistoryTarget.SmbFolderGallery -> {
                val source = withIOContext { SmbRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val remote = target.remoteDir.trim('/')
                if (Settings.photoGridMode.value) {
                    openSmbFolderPhotoGrid(
                        sourceId = source.id,
                        remoteDir = remote,
                        fromHistory = true,
                    )
                    return@launch
                }
                val parentRel = parentRelativeOfFile(remote)
                val gi = BaseGalleryInfo(
                    gid = info.gid,
                    token = SMB_FOLDER_TOKEN,
                    title = info.title ?: remote.substringAfterLast('/').ifEmpty { "Share" },
                    pages = info.pages,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = info.thumbKey,
                    uploader = "${source.id}\u0000$remote",
                    category = 2,
                )
                val names = FolderGalleryIndex.loadSmb(
                    source.id,
                    SmbGateway.sourceConfigKey(source),
                    remote,
                ).orEmpty()
                openFromHistoryWithBackStack(
                    pushParentDir = {
                        openSmbBrowseDir(
                            sourceId = source.id,
                            remoteDir = parentRel,
                            fromHistory = true,
                        )
                    },
                    // Same names the folder view would pass; empty → reader uses index again.
                    openContent = { navToSmbFolderReader(source.id, remote, names, gi) },
                )
            }
            is LocalHistoryTarget.WebDavFolderGallery -> {
                val source = withIOContext { WebDavRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val remote = target.remoteDir.trim('/')
                if (Settings.photoGridMode.value) {
                    openWebDavFolderPhotoGrid(
                        sourceId = source.id,
                        remoteDir = remote,
                        fromHistory = true,
                    )
                    return@launch
                }
                val parentRel = parentRelativeOfFile(remote)
                val gi = BaseGalleryInfo(
                    gid = info.gid,
                    token = WEBDAV_FOLDER_TOKEN,
                    title = info.title ?: remote.substringAfterLast('/').ifEmpty { "WebDAV" },
                    pages = info.pages,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = info.thumbKey,
                    uploader = "${source.id}\u0000$remote",
                    category = 3,
                )
                val names = FolderGalleryIndex.loadWebDav(
                    source.id,
                    WebDavGateway.sourceConfigKey(source),
                    remote,
                ).orEmpty()
                openFromHistoryWithBackStack(
                    pushParentDir = {
                        openWebDavBrowseDir(
                            sourceId = source.id,
                            remoteDir = parentRel,
                            fromHistory = true,
                        )
                    },
                    openContent = { navToWebDavFolderReader(source.id, remote, names, gi) },
                )
            }
            is LocalHistoryTarget.LocalArchive -> {
                val ebookName = info.title
                    ?: target.path.substringAfterLast('/').substringAfterLast('\\')
                if (isEbookFileName(ebookName) || isEbookFileName(target.path)) {
                    withIOContext {
                        LocalHistory.recordLocalArchive(
                            target.path,
                            title = ebookName,
                            coverPath = info.thumbKey,
                            pages = info.pages,
                        )
                    }
                }
                // Optional parent browse path under alwaysExitToDir
                // (fromHistory FAB still jumps straight to History).
                val parent = withIOContext {
                    LocalLibrary.resolveArchiveBrowseParent(target.path)
                }
                val parentPrefersMediaStore = if (parent != null) {
                    withIOContext { LocalLibrary.loadRoot(parent.rootId)?.prefersMediaStore } ?: false
                } else {
                    false
                }
                openFromHistoryWithBackStack(
                    pushParentDir = {
                        if (parent != null) {
                            openLocalBrowseDir(
                                rootId = parent.rootId,
                                rootDisplayName = parent.rootDisplayName,
                                rootPath = parent.rootPath,
                                relativePath = parent.parentRelativePath,
                                preferMediaStore = parentPrefersMediaStore,
                                fromHistory = true,
                            )
                        }
                    },
                    openContent = { navToReader(target.path) },
                )
            }
            is LocalHistoryTarget.SmbStreamArchive -> {
                val source = withIOContext { SmbRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val remote = target.remotePath.trim('/')
                val parentRel = parentRelativeOfFile(remote)
                // Always smba: (progress gid). Drop legacy smb-archive: history row if present.
                val progressGid = stableGalleryId(source.id, "smba:$remote")
                if (info.gid != progressGid) {
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                }
                val gi = BaseGalleryInfo(
                    gid = progressGid,
                    token = SMB_ARCHIVE_TOKEN,
                    title = info.title ?: remote.substringAfterLast('/'),
                    pages = info.pages,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = info.thumbKey ?: HistoryThumbKey.smbArchive(source.id, remote),
                    uploader = "${source.id}\u0000$remote",
                    category = 1,
                )
                if (isEbookFileName(gi.title.orEmpty()) || isEbookFileName(remote)) {
                    withIOContext {
                        LocalHistory.recordSmbStreamArchive(
                            source.id,
                            remote,
                            title = gi.title,
                            pages = gi.pages,
                            info = gi,
                            thumbKey = gi.thumbKey,
                        )
                    }
                }
                openFromHistoryWithBackStack(
                    pushParentDir = {
                        openSmbBrowseDir(
                            sourceId = source.id,
                            remoteDir = parentRel,
                            fromHistory = true,
                        )
                    },
                    openContent = { navToSmbStreamArchiveReader(source.id, remote, gi) },
                )
            }
            is LocalHistoryTarget.WebDavStreamArchive -> {
                val source = withIOContext { WebDavRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val remote = target.remotePath.trim('/')
                val parentRel = parentRelativeOfFile(remote)
                val progressGid = stableGalleryId(source.id, "dava:$remote")
                if (info.gid != progressGid) {
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                }
                val gi = BaseGalleryInfo(
                    gid = progressGid,
                    token = WEBDAV_ARCHIVE_TOKEN,
                    title = info.title ?: remote.substringAfterLast('/'),
                    pages = info.pages,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = info.thumbKey ?: HistoryThumbKey.webdavArchive(source.id, remote),
                    uploader = "${source.id}\u0000$remote",
                    category = 1,
                )
                if (isEbookFileName(gi.title.orEmpty()) || isEbookFileName(remote)) {
                    withIOContext {
                        LocalHistory.recordWebDavStreamArchive(
                            source.id,
                            remote,
                            title = gi.title,
                            pages = gi.pages,
                            info = gi,
                            thumbKey = gi.thumbKey,
                        )
                    }
                }
                openFromHistoryWithBackStack(
                    pushParentDir = {
                        openWebDavBrowseDir(
                            sourceId = source.id,
                            remoteDir = parentRel,
                            fromHistory = true,
                        )
                    },
                    openContent = { navToWebDavStreamArchiveReader(source.id, remote, gi) },
                )
            }
            // Videos / regular files: open or play in place; stay on History so
            // leaving the external app returns here (no helper browse stack).
            is LocalHistoryTarget.LocalFile -> {
                val path = target.path
                val name = info.title
                    ?: path.substringAfterLast('/').substringAfterLast('\\')
                if (isPdfFileName(name) || isPdfFileName(path) ||
                    isEpubFileName(name) || isEpubFileName(path)
                ) {
                    if (isEbookFileName(name) || isEbookFileName(path)) {
                        withIOContext {
                            LocalHistory.recordLocalFile(path, title = name, thumbKey = info.thumbKey)
                        }
                    }
                    navToReader(path)
                    return@launch
                }
                if (OpenFileExternally.shouldOpenHtmlInBrowser(name) ||
                    OpenFileExternally.shouldOpenHtmlInBrowser(path)
                ) {
                    withIOContext {
                        LocalHistory.recordLocalFile(path, title = name, thumbKey = info.thumbKey)
                        try {
                            OpenFileExternally.openLocalHtml(
                                context,
                                path,
                                displayName = name,
                                mimeType = mimeTypeForFileName(name),
                                incognito = Settings.openHtmlInIncognito.value,
                            )
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                    return@launch
                }
                if (isEbookFileName(name) || isEbookFileName(path)) {
                    val gid = stableGalleryId(0L, "local-file:$path")
                    val page = withIOContext {
                        runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                    }
                    withIOContext {
                        LocalHistory.recordLocalFile(path, title = name, thumbKey = info.thumbKey)
                        try {
                            OpenPdfExternally.openInternalLocal(
                                context,
                                path,
                                displayName = name,
                                progressGid = gid,
                                startPage = page,
                            )
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(
                                    R.string.pdf_reader_open_failed,
                                    e.message ?: e.toString(),
                                ),
                            )
                        }
                    }
                    return@launch
                }
                val mime = mimeTypeForFileName(name)
                val isVideo = isVideoFileName(name) || isVideoFileName(path)
                withIOContext {
                    LocalHistory.recordLocalFile(path, title = name, thumbKey = info.thumbKey)
                    try {
                        val inApp = isVideo && localFolderVideoUsesMedia3(
                            media3 = Settings.useMedia3Player.value,
                            media3ForLocalFiles = Settings.useMedia3PlayerForLocalFiles.value,
                        )
                        if (inApp) {
                            OpenFileExternally.playLocal(context, path, displayName = name, mimeType = mime)
                        } else {
                            OpenFileExternally.openLocal(context, path, displayName = name, mimeType = mime)
                        }
                    } catch (e: Throwable) {
                        snackbar(
                            context.getString(R.string.browse_open_failed) +
                                " " + (e.message ?: e.toString()),
                        )
                    }
                }
            }
            is LocalHistoryTarget.SmbFile -> {
                val source = withIOContext { SmbRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val remote = target.remotePath.trim('/')
                val name = info.title
                    ?: remote.substringAfterLast('/').substringAfterLast('\\')
                if (isPdfFileName(name) || isPdfFileName(remote) ||
                    isEpubFileName(name) || isEpubFileName(remote)
                ) {
                    if (isEbookFileName(name) || isEbookFileName(remote)) {
                        withIOContext {
                            LocalHistory.recordSmbFile(
                                source.id,
                                remote,
                                title = name,
                                thumbKey = info.thumbKey,
                            )
                        }
                    }
                    navToSmbStreamArchiveReader(source.id, remote)
                    return@launch
                }
                if (OpenFileExternally.shouldOpenHtmlInBrowser(name) ||
                    OpenFileExternally.shouldOpenHtmlInBrowser(remote)
                ) {
                    withIOContext {
                        LocalHistory.recordSmbFile(
                            source.id,
                            remote,
                            title = name,
                            thumbKey = info.thumbKey,
                        )
                        try {
                            OpenFileExternally.openSmbHtml(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                mimeType = mimeTypeForFileName(name),
                                incognito = Settings.openHtmlInIncognito.value,
                            )
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                    return@launch
                }
                if (isEbookFileName(name) || isEbookFileName(remote)) {
                    val gid = stableGalleryId(source.id, "smbf:$remote")
                    val page = withIOContext {
                        runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                    }
                    withIOContext {
                        LocalHistory.recordSmbFile(
                            source.id,
                            remote,
                            title = name,
                            thumbKey = info.thumbKey,
                        )
                        try {
                            OpenPdfExternally.openInternalSmb(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                progressGid = gid,
                                startPage = page,
                            )
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                    return@launch
                }
                val mime = mimeTypeForFileName(name)
                val isVideo = isVideoFileName(name) || isVideoFileName(remote)
                withIOContext {
                    LocalHistory.recordSmbFile(
                        source.id,
                        remote,
                        title = name,
                        thumbKey = info.thumbKey,
                    )
                    try {
                        if (isVideo && Settings.useMedia3Player.value) {
                            OpenFileExternally.playSmb(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                mimeType = mime,
                            )
                        } else {
                            OpenFileExternally.openSmb(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                mimeType = mime,
                            )
                        }
                    } catch (e: Throwable) {
                        snackbar(
                            context.getString(R.string.browse_open_failed) +
                                " " + (e.message ?: e.toString()),
                        )
                    }
                }
            }
            is LocalHistoryTarget.WebDavFile -> {
                val source = withIOContext { WebDavRepository.load(target.sourceId) }
                if (source == null) {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                    return@launch
                }
                val remote = target.remotePath.trim('/')
                val name = info.title
                    ?: remote.substringAfterLast('/').substringAfterLast('\\')
                if (isPdfFileName(name) || isPdfFileName(remote) ||
                    isEpubFileName(name) || isEpubFileName(remote)
                ) {
                    if (isEbookFileName(name) || isEbookFileName(remote)) {
                        withIOContext {
                            LocalHistory.recordWebDavFile(
                                source.id,
                                remote,
                                title = name,
                                thumbKey = info.thumbKey,
                            )
                        }
                    }
                    navToWebDavStreamArchiveReader(source.id, remote)
                    return@launch
                }
                if (OpenFileExternally.shouldOpenHtmlInBrowser(name) ||
                    OpenFileExternally.shouldOpenHtmlInBrowser(remote)
                ) {
                    withIOContext {
                        LocalHistory.recordWebDavFile(
                            source.id,
                            remote,
                            title = name,
                            thumbKey = info.thumbKey,
                        )
                        try {
                            OpenFileExternally.openWebDavHtml(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                mimeType = mimeTypeForFileName(name),
                                incognito = Settings.openHtmlInIncognito.value,
                            )
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                    return@launch
                }
                if (isEbookFileName(name) || isEbookFileName(remote)) {
                    val gid = stableGalleryId(source.id, "davf:$remote")
                    val page = withIOContext {
                        runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                    }
                    withIOContext {
                        LocalHistory.recordWebDavFile(
                            source.id,
                            remote,
                            title = name,
                            thumbKey = info.thumbKey,
                        )
                        try {
                            OpenPdfExternally.openInternalWebDav(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                progressGid = gid,
                                startPage = page,
                            )
                        } catch (e: Throwable) {
                            snackbar(
                                context.getString(R.string.browse_open_failed) +
                                    " " + (e.message ?: e.toString()),
                            )
                        }
                    }
                    return@launch
                }
                val mime = mimeTypeForFileName(name)
                val isVideo = isVideoFileName(name) || isVideoFileName(remote)
                withIOContext {
                    LocalHistory.recordWebDavFile(
                        source.id,
                        remote,
                        title = name,
                        thumbKey = info.thumbKey,
                    )
                    try {
                        if (isVideo && Settings.useMedia3Player.value) {
                            OpenFileExternally.playWebDav(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                mimeType = mime,
                            )
                        } else {
                            OpenFileExternally.openWebDav(
                                context,
                                source.id,
                                remote,
                                displayName = name,
                                mimeType = mime,
                            )
                        }
                    } catch (e: Throwable) {
                        snackbar(
                            context.getString(R.string.browse_open_failed) +
                                " " + (e.message ?: e.toString()),
                        )
                    }
                }
            }
            is LocalHistoryTarget.Orphan -> {
                // Legacy "local" browse rows without path metadata, or foreign EH history.
                val local = withIOContext { LocalLibrary.loadGallery(target.gid) }
                if (local != null) {
                    if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        navToReader(local.contentPath)
                    } else if (Settings.photoGridMode.value) {
                        val root = withIOContext { LocalLibrary.loadRoot(local.rootId) }
                        val rootPath = root?.let { LocalLibrary.rootPath(it) }
                        if (root == null || rootPath == null) {
                            navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                        } else {
                            withIOContext { EhDB.putHistoryInfo(info) }
                            openLocalFolderPhotoGrid(
                                rootId = root.id,
                                rootDisplayName = root.displayName,
                                rootPath = rootPath,
                                relativePath = local.relativePath,
                                preferMediaStore = root.prefersMediaStore,
                                title = local.title,
                                fromHistory = true,
                            )
                        }
                    } else {
                        navToLocalFolderReader(local.contentPath, local.toBaseGalleryInfo())
                    }
                } else {
                    snackbar(string(R.string.history_unavailable))
                    withIOContext { EhDB.deleteHistoryInfo(info) }
                }
            }
        }
    }
}
