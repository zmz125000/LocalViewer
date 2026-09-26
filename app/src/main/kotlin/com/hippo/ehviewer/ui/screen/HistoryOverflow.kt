package com.hippo.ehviewer.ui.screen

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.library.BrowseFavorites
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.isHtmlFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isPdfOrEbookFileName
import com.hippo.ehviewer.library.isVideoFileName
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.resolveRelative
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.OpenPdfExternally
import com.hippo.ehviewer.ui.main.BrowseOverflowActions
import com.hippo.ehviewer.ui.main.BrowseOverflowKind
import com.hippo.ehviewer.ui.main.BrowseSaveAs
import com.hippo.ehviewer.ui.main.HttpShare
import com.hippo.ehviewer.ui.main.HttpShareItem
import com.hippo.ehviewer.ui.main.awaitHttpShareQr
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.util.addTextToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.tarsin.snackbar
import okio.Path.Companion.toPath

internal fun historyOverflowKind(info: GalleryInfo): BrowseOverflowKind {
    val name = LocalHistory.fileNameOfHistory(info)
    return when (LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalBrowseFolder,
        is LocalHistoryTarget.SmbBrowseFolder,
        is LocalHistoryTarget.WebDavBrowseFolder,
        -> BrowseOverflowKind.Common
        is LocalHistoryTarget.LocalFolderGallery,
        is LocalHistoryTarget.SmbFolderGallery,
        is LocalHistoryTarget.WebDavFolderGallery,
        -> BrowseOverflowKind.Gallery
        is LocalHistoryTarget.LocalArchive,
        is LocalHistoryTarget.SmbStreamArchive,
        is LocalHistoryTarget.WebDavStreamArchive,
        -> if (isPdfOrEbookFileName(name)) BrowseOverflowKind.Pdf else BrowseOverflowKind.Gallery
        is LocalHistoryTarget.LocalFile,
        is LocalHistoryTarget.SmbFile,
        is LocalHistoryTarget.WebDavFile,
        -> when {
            isHtmlFileName(name) -> BrowseOverflowKind.Webpage
            isPdfOrEbookFileName(name) -> BrowseOverflowKind.Pdf
            isVideoFileName(name) -> BrowseOverflowKind.Video
            else -> BrowseOverflowKind.Common
        }
        is LocalHistoryTarget.LibraryGallery -> when {
            isPdfOrEbookFileName(name) -> BrowseOverflowKind.Pdf
            else -> BrowseOverflowKind.Gallery
        }
        is LocalHistoryTarget.Orphan -> BrowseOverflowKind.Common
    }
}

internal fun historyOverflowHasPhotoGrid(info: GalleryInfo): Boolean = when (LocalHistory.parse(info)) {
    is LocalHistoryTarget.LocalFolderGallery,
    is LocalHistoryTarget.SmbFolderGallery,
    is LocalHistoryTarget.WebDavFolderGallery,
    -> true
    is LocalHistoryTarget.LibraryGallery -> info.category != 1
    else -> false
}

internal fun historyOverflowHasImageReader(info: GalleryInfo): Boolean {
    if (historyOverflowKind(info) != BrowseOverflowKind.Pdf) return false
    return when (LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalArchive,
        is LocalHistoryTarget.SmbStreamArchive,
        is LocalHistoryTarget.WebDavStreamArchive,
        -> true
        is LocalHistoryTarget.LibraryGallery -> info.category == 1
        else -> false
    }
}

internal fun historyOverflowHasFavorite(info: GalleryInfo): Boolean = when (LocalHistory.parse(info)) {
    is LocalHistoryTarget.LocalBrowseFolder,
    is LocalHistoryTarget.SmbBrowseFolder,
    is LocalHistoryTarget.WebDavBrowseFolder,
    is LocalHistoryTarget.LocalFolderGallery,
    is LocalHistoryTarget.SmbFolderGallery,
    is LocalHistoryTarget.WebDavFolderGallery,
    -> true
    is LocalHistoryTarget.LibraryGallery -> info.category != 1
    else -> false
}

internal fun historyItemFavorited(info: GalleryInfo, favoriteKeys: Set<String>): Boolean {
    if (!historyOverflowHasFavorite(info)) return false
    return when (val target = LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalBrowseFolder ->
            BrowseFavorites.localFolderKey(target.rootId, target.relativePath) in favoriteKeys
        is LocalHistoryTarget.LocalFolderGallery ->
            BrowseFavorites.localFolderKey(target.rootId, target.relativePath) in favoriteKeys
        is LocalHistoryTarget.SmbBrowseFolder ->
            BrowseFavorites.smbFolderKey(target.sourceId, target.relativePath) in favoriteKeys
        is LocalHistoryTarget.SmbFolderGallery ->
            BrowseFavorites.smbFolderKey(target.sourceId, target.remoteDir) in favoriteKeys
        is LocalHistoryTarget.WebDavBrowseFolder ->
            BrowseFavorites.webDavFolderKey(target.sourceId, target.relativePath) in favoriteKeys
        is LocalHistoryTarget.WebDavFolderGallery ->
            BrowseFavorites.webDavFolderKey(target.sourceId, target.remoteDir) in favoriteKeys
        is LocalHistoryTarget.LibraryGallery ->
            BrowseFavorites.galleryKey(target.galleryId) in favoriteKeys
        else -> false
    }
}

context(snackbarHost: SnackbarHostState, scope: CoroutineScope, dialog: DialogState)
internal fun historyOverflowActions(
    context: Context,
    info: GalleryEntity,
    favoriteKeys: Set<String>,
    openEntry: () -> Unit,
    openPhotoGrid: () -> Unit,
    openFolder: () -> Unit,
    imageReader: () -> Unit,
): BrowseOverflowActions {
    val env = HistoryOverflowEnv(context, scope, snackbarHost, dialog)
    val kind = historyOverflowKind(info)
    val share = when (kind) {
        BrowseOverflowKind.Common ->
            if (historyOverflowHasFavorite(info)) null else ({ env.share(info) })
        else -> ({ env.share(info) })
    }
    val openWith = when (kind) {
        BrowseOverflowKind.Common ->
            if (historyOverflowHasFavorite(info)) null else ({ env.openWith(info) })
        else -> ({ env.openWith(info) })
    }
    return BrowseOverflowActions(
        kind = kind,
        favorited = historyItemFavorited(info, favoriteKeys),
        onFavorite = if (historyOverflowHasFavorite(info)) {
            { env.toggleFavorite(info) }
        } else {
            null
        },
        onSaveAs = { env.saveAs(info) },
        onShare = share,
        onShareViaHttp = env.httpShare(info),
        onOpenFolder = openFolder,
        onOpenWith = openWith,
        onRead = when {
            kind == BrowseOverflowKind.Gallery -> openEntry
            historyOverflowHasImageReader(info) -> imageReader
            else -> null
        },
        onPhotoGrid = if (historyOverflowHasPhotoGrid(info)) openPhotoGrid else null,
        onPlay = when (kind) {
            BrowseOverflowKind.Video, BrowseOverflowKind.Pdf, BrowseOverflowKind.Webpage -> ({ env.play(info) })
            else -> null
        },
        onExternalPlayer = when (kind) {
            BrowseOverflowKind.Video, BrowseOverflowKind.Pdf -> ({ env.externalPlayer(info) })
            else -> null
        },
        onCopyUrl = when (kind) {
            BrowseOverflowKind.Video, BrowseOverflowKind.Webpage -> ({ env.copyUrl(info) })
            else -> null
        },
        onOpenInBrowser = if (kind == BrowseOverflowKind.Webpage) {
            { env.openHtml(info, incognito = false) }
        } else {
            null
        },
        onOpenIncognito = if (kind == BrowseOverflowKind.Webpage) {
            { env.openHtml(info, incognito = true) }
        } else {
            null
        },
        onUnsupported = {
            env.io {
                snackbar(context.getString(R.string.browse_action_not_supported))
            }
        },
    )
}

private class HistoryOverflowEnv(
    val context: Context,
    val scope: CoroutineScope,
    val snackbarHost: SnackbarHostState,
    val dialog: DialogState,
) {
    fun io(block: suspend context(SnackbarHostState, DialogState, Context) () -> Unit) {
        scope.launch(Dispatchers.IO) {
            with(snackbarHost) {
                with(dialog) {
                    with(context) {
                        block()
                    }
                }
            }
        }
    }
}

private fun historyDisplayName(info: GalleryInfo): String = info.title?.takeIf { it.isNotBlank() } ?: LocalHistory.fileNameOfHistory(info)

private fun HistoryOverflowEnv.toggleFavorite(info: GalleryInfo) {
    when (val target = LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalBrowseFolder ->
            BrowseFavorites.toggleLocalFolder(target.rootId, target.relativePath, info.thumbKey)
        is LocalHistoryTarget.LocalFolderGallery ->
            BrowseFavorites.toggleLocalFolder(target.rootId, target.relativePath, info.thumbKey)
        is LocalHistoryTarget.SmbBrowseFolder ->
            BrowseFavorites.toggleSmbFolder(target.sourceId, target.relativePath, info.thumbKey)
        is LocalHistoryTarget.SmbFolderGallery ->
            BrowseFavorites.toggleSmbFolder(target.sourceId, target.remoteDir, info.thumbKey)
        is LocalHistoryTarget.WebDavBrowseFolder ->
            BrowseFavorites.toggleWebDavFolder(target.sourceId, target.relativePath, info.thumbKey)
        is LocalHistoryTarget.WebDavFolderGallery ->
            BrowseFavorites.toggleWebDavFolder(target.sourceId, target.remoteDir, info.thumbKey)
        is LocalHistoryTarget.LibraryGallery ->
            BrowseFavorites.toggleGallery(target.galleryId)
        else -> Unit
    }
}

private fun HistoryOverflowEnv.saveAs(info: GalleryEntity) {
    val name = historyDisplayName(info)
    io {
        when (val target = LocalHistory.parse(info)) {
            is LocalHistoryTarget.LocalBrowseFolder -> {
                val dir = localAbsPath(target.rootId, target.relativePath) ?: return@io
                BrowseSaveAs.saveLocalFolder(dir, name, target.relativePath)
            }
            is LocalHistoryTarget.LocalFolderGallery -> {
                val dir = localAbsPath(target.rootId, target.relativePath) ?: return@io
                BrowseSaveAs.saveLocalFolder(dir, name, target.relativePath)
            }
            is LocalHistoryTarget.LocalArchive ->
                BrowseSaveAs.saveLocalFile(target.path.toPath(), name)
            is LocalHistoryTarget.LocalFile ->
                BrowseSaveAs.saveLocalFile(target.path.toPath(), name)
            is LocalHistoryTarget.SmbBrowseFolder ->
                BrowseSaveAs.saveSmbFolder(target.sourceId, target.relativePath, name)
            is LocalHistoryTarget.SmbFolderGallery ->
                BrowseSaveAs.saveSmbFolder(target.sourceId, target.remoteDir, name)
            is LocalHistoryTarget.SmbStreamArchive ->
                BrowseSaveAs.saveSmbFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.SmbFile ->
                BrowseSaveAs.saveSmbFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.WebDavBrowseFolder ->
                BrowseSaveAs.saveWebDavFolder(target.sourceId, target.relativePath, name)
            is LocalHistoryTarget.WebDavFolderGallery ->
                BrowseSaveAs.saveWebDavFolder(target.sourceId, target.remoteDir, name)
            is LocalHistoryTarget.WebDavStreamArchive ->
                BrowseSaveAs.saveWebDavFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.WebDavFile ->
                BrowseSaveAs.saveWebDavFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.LibraryGallery -> {
                val local = LocalLibrary.loadGallery(target.galleryId) ?: return@io
                if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                    BrowseSaveAs.saveLocalFile(local.contentPath.toPath(), name)
                } else {
                    val dir = localAbsPath(local.rootId, local.relativePath) ?: return@io
                    BrowseSaveAs.saveLocalFolder(dir, name, local.relativePath)
                }
            }
            is LocalHistoryTarget.Orphan -> Unit
        }
    }
}

private fun HistoryOverflowEnv.share(info: GalleryEntity) {
    val name = historyDisplayName(info)
    io {
        when (val target = LocalHistory.parse(info)) {
            is LocalHistoryTarget.LocalArchive ->
                BrowseSaveAs.shareLocalFile(target.path.toPath(), name)
            is LocalHistoryTarget.LocalFile ->
                BrowseSaveAs.shareLocalFile(target.path.toPath(), name)
            is LocalHistoryTarget.SmbStreamArchive ->
                BrowseSaveAs.shareSmbFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.SmbFile ->
                BrowseSaveAs.shareSmbFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.WebDavStreamArchive ->
                BrowseSaveAs.shareWebDavFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.WebDavFile ->
                BrowseSaveAs.shareWebDavFile(target.sourceId, target.remotePath, name)
            is LocalHistoryTarget.LibraryGallery -> {
                val local = LocalLibrary.loadGallery(target.galleryId) ?: return@io
                if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                    BrowseSaveAs.shareLocalFile(local.contentPath.toPath(), name)
                }
            }
            else -> Unit
        }
    }
}

private fun HistoryOverflowEnv.httpShare(info: GalleryEntity): (() -> Unit)? {
    val name = historyDisplayName(info)
    fun share(block: suspend () -> HttpShareItem): () -> Unit = {
        io {
            try {
                val item = block()
                withUIContext { awaitHttpShareQr(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.browse_http_share_failed) + " " +
                        (e.message ?: e.toString()),
                )
            }
        }
    }
    return when (val target = LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalBrowseFolder,
        is LocalHistoryTarget.LocalFolderGallery,
        -> {
            val rel = when (target) {
                is LocalHistoryTarget.LocalBrowseFolder -> target.relativePath
                is LocalHistoryTarget.LocalFolderGallery -> target.relativePath
            }
            val rootId = when (target) {
                is LocalHistoryTarget.LocalBrowseFolder -> target.rootId
                is LocalHistoryTarget.LocalFolderGallery -> target.rootId
            }
            share {
                val abs = localAbsPath(rootId, rel) ?: error("Missing folder")
                val zipRel = ZipAsDirListing.splitZipBrowsePath(rel)?.first
                if (zipRel != null) {
                    val zipAbs = localAbsPath(rootId, zipRel) ?: abs
                    val zipName = zipRel.substringAfterLast('/').ifEmpty { name }
                    HttpShare.startLocalFile(
                        context,
                        zipAbs.toString(),
                        zipName,
                        mimeTypeForFileName(zipName),
                    )
                } else {
                    HttpShare.startLocalFolder(context, abs.toString(), name)
                }
            }
        }
        is LocalHistoryTarget.LocalArchive -> share {
            HttpShare.startLocalFile(context, target.path, name, mimeTypeForFileName(name))
        }
        is LocalHistoryTarget.LocalFile -> share {
            HttpShare.startLocalFile(context, target.path, name, mimeTypeForFileName(name))
        }
        is LocalHistoryTarget.SmbBrowseFolder -> smbFolderHttp(context, target.sourceId, target.relativePath, name, ::share)
        is LocalHistoryTarget.SmbFolderGallery -> smbFolderHttp(context, target.sourceId, target.remoteDir, name, ::share)
        is LocalHistoryTarget.SmbStreamArchive -> share {
            HttpShare.startSmbFile(context, target.sourceId, target.remotePath, name, mimeTypeForFileName(name))
        }
        is LocalHistoryTarget.SmbFile -> share {
            HttpShare.startSmbFile(context, target.sourceId, target.remotePath, name, mimeTypeForFileName(name))
        }
        is LocalHistoryTarget.WebDavBrowseFolder -> davFolderHttp(context, target.sourceId, target.relativePath, name, ::share)
        is LocalHistoryTarget.WebDavFolderGallery -> davFolderHttp(context, target.sourceId, target.remoteDir, name, ::share)
        is LocalHistoryTarget.WebDavStreamArchive -> share {
            HttpShare.startWebDavFile(context, target.sourceId, target.remotePath, name, mimeTypeForFileName(name))
        }
        is LocalHistoryTarget.WebDavFile -> share {
            HttpShare.startWebDavFile(context, target.sourceId, target.remotePath, name, mimeTypeForFileName(name))
        }
        is LocalHistoryTarget.LibraryGallery -> share {
            val local = LocalLibrary.loadGallery(target.galleryId) ?: error("Missing gallery")
            if (local.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                HttpShare.startLocalFile(
                    context,
                    local.contentPath,
                    name,
                    mimeTypeForFileName(name),
                )
            } else {
                val abs = localAbsPath(local.rootId, local.relativePath) ?: error("Missing folder")
                HttpShare.startLocalFolder(context, abs.toString(), name)
            }
        }
        is LocalHistoryTarget.Orphan -> null
    }
}

private fun smbFolderHttp(
    context: Context,
    sourceId: Long,
    remote: String,
    name: String,
    share: (suspend () -> HttpShareItem) -> () -> Unit,
): () -> Unit {
    HttpShare.zipFileRelativeForFolderShare(remote)?.let { zipRel ->
        val zipName = zipRel.substringAfterLast('/').ifEmpty { name }
        return share {
            HttpShare.startSmbFile(context, sourceId, zipRel, zipName, mimeTypeForFileName(zipName))
        }
    }
    return share { HttpShare.startSmbFolder(context, sourceId, remote, name) }
}

private fun davFolderHttp(
    context: Context,
    sourceId: Long,
    remote: String,
    name: String,
    share: (suspend () -> HttpShareItem) -> () -> Unit,
): () -> Unit {
    HttpShare.zipFileRelativeForFolderShare(remote)?.let { zipRel ->
        val zipName = zipRel.substringAfterLast('/').ifEmpty { name }
        return share {
            HttpShare.startWebDavFile(context, sourceId, zipRel, zipName, mimeTypeForFileName(zipName))
        }
    }
    return share { HttpShare.startWebDavFolder(context, sourceId, remote, name) }
}

private fun HistoryOverflowEnv.openWith(info: GalleryEntity) {
    openExternal(info, asFile = true, usePreferred = false)
}

private fun HistoryOverflowEnv.externalPlayer(info: GalleryEntity) {
    val name = historyDisplayName(info)
    if (isPdfFileName(name)) {
        io {
            try {
                when (val target = LocalHistory.parse(info)) {
                    is LocalHistoryTarget.LocalArchive ->
                        OpenPdfExternally.openLocal(context, target.path, displayName = name)
                    is LocalHistoryTarget.LocalFile ->
                        OpenPdfExternally.openLocal(context, target.path, displayName = name)
                    is LocalHistoryTarget.SmbStreamArchive ->
                        OpenPdfExternally.openSmb(context, target.sourceId, target.remotePath, displayName = name)
                    is LocalHistoryTarget.SmbFile ->
                        OpenPdfExternally.openSmb(context, target.sourceId, target.remotePath, displayName = name)
                    is LocalHistoryTarget.WebDavStreamArchive ->
                        OpenPdfExternally.openWebDav(context, target.sourceId, target.remotePath, displayName = name)
                    is LocalHistoryTarget.WebDavFile ->
                        OpenPdfExternally.openWebDav(context, target.sourceId, target.remotePath, displayName = name)
                    is LocalHistoryTarget.LibraryGallery -> {
                        val local = LocalLibrary.loadGallery(target.galleryId) ?: return@io
                        OpenPdfExternally.openLocal(context, local.contentPath, displayName = name)
                    }
                    else -> openExternal(info, asFile = false, usePreferred = true)
                }
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.open_pdf_external_failed, e.message ?: e.toString()),
                )
            }
        }
    } else {
        openExternal(info, asFile = false, usePreferred = true)
    }
}

private fun HistoryOverflowEnv.play(info: GalleryEntity) {
    val name = historyDisplayName(info)
    if (isPdfOrEbookFileName(name)) {
        io {
            try {
                when (val target = LocalHistory.parse(info)) {
                    is LocalHistoryTarget.LocalArchive -> {
                        val gid = LocalHistory.galleryInfoForLocalArchive(target.path, title = name).gid
                        val page = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                        OpenPdfExternally.openInternalLocal(context, target.path, name, gid, page)
                    }
                    is LocalHistoryTarget.LocalFile -> {
                        val gid = stableGalleryId(0L, "local-file:${target.path}")
                        val page = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                        OpenPdfExternally.openInternalLocal(context, target.path, name, gid, page)
                    }
                    is LocalHistoryTarget.SmbStreamArchive -> {
                        val gid = stableGalleryId(target.sourceId, "smba:${target.remotePath.trim('/')}")
                        val page = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                        OpenPdfExternally.openInternalSmb(
                            context,
                            target.sourceId,
                            target.remotePath,
                            name,
                            gid,
                            page,
                        )
                    }
                    is LocalHistoryTarget.SmbFile -> {
                        val gid = stableGalleryId(target.sourceId, "smbf:${target.remotePath.trim('/')}")
                        val page = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                        OpenPdfExternally.openInternalSmb(
                            context,
                            target.sourceId,
                            target.remotePath,
                            name,
                            gid,
                            page,
                        )
                    }
                    is LocalHistoryTarget.WebDavStreamArchive -> {
                        val gid = stableGalleryId(target.sourceId, "dava:${target.remotePath.trim('/')}")
                        val page = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                        OpenPdfExternally.openInternalWebDav(
                            context,
                            target.sourceId,
                            target.remotePath,
                            name,
                            gid,
                            page,
                        )
                    }
                    is LocalHistoryTarget.WebDavFile -> {
                        val gid = stableGalleryId(target.sourceId, "davf:${target.remotePath.trim('/')}")
                        val page = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
                        OpenPdfExternally.openInternalWebDav(
                            context,
                            target.sourceId,
                            target.remotePath,
                            name,
                            gid,
                            page,
                        )
                    }
                    is LocalHistoryTarget.LibraryGallery -> {
                        val local = LocalLibrary.loadGallery(target.galleryId) ?: return@io
                        val page = runCatching { EhDB.getReadProgress(local.id) }.getOrDefault(0)
                        OpenPdfExternally.openInternalLocal(
                            context,
                            local.contentPath,
                            name,
                            local.id,
                            page,
                        )
                    }
                    else -> Unit
                }
            } catch (e: Throwable) {
                snackbar(
                    context.getString(R.string.pdf_reader_open_failed, e.message ?: e.toString()),
                )
            }
        }
        return
    }
    io {
        try {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalFile -> OpenFileExternally.playLocal(
                    context,
                    target.path,
                    displayName = name,
                    mimeType = mimeTypeForFileName(name),
                )
                is LocalHistoryTarget.SmbFile -> OpenFileExternally.playSmb(
                    context,
                    target.sourceId,
                    target.remotePath,
                    displayName = name,
                    mimeType = mimeTypeForFileName(name),
                )
                is LocalHistoryTarget.WebDavFile -> OpenFileExternally.playWebDav(
                    context,
                    target.sourceId,
                    target.remotePath,
                    displayName = name,
                    mimeType = mimeTypeForFileName(name),
                )
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = LocalLibrary.loadGallery(target.galleryId) ?: return@io
                    OpenFileExternally.playLocal(
                        context,
                        local.contentPath,
                        displayName = name,
                        mimeType = mimeTypeForFileName(name),
                    )
                }
                else -> Unit
            }
        } catch (e: Throwable) {
            snackbar(
                context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
            )
        }
    }
}

private fun HistoryOverflowEnv.openExternal(
    info: GalleryEntity,
    asFile: Boolean,
    usePreferred: Boolean,
) {
    val name = historyDisplayName(info)
    val mime = mimeTypeForFileName(name)
    io {
        try {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalArchive -> OpenFileExternally.openLocal(
                    context,
                    target.path,
                    displayName = name,
                    mimeType = mime,
                    asFile = asFile,
                    usePreferredPlayer = usePreferred,
                )
                is LocalHistoryTarget.LocalFile -> OpenFileExternally.openLocal(
                    context,
                    target.path,
                    displayName = name,
                    mimeType = mime,
                    asFile = asFile,
                    usePreferredPlayer = usePreferred,
                )
                is LocalHistoryTarget.SmbStreamArchive -> OpenFileExternally.openSmb(
                    context,
                    target.sourceId,
                    target.remotePath,
                    displayName = name,
                    mimeType = mime,
                    asFile = asFile,
                    usePreferredPlayer = usePreferred,
                )
                is LocalHistoryTarget.SmbFile -> OpenFileExternally.openSmb(
                    context,
                    target.sourceId,
                    target.remotePath,
                    displayName = name,
                    mimeType = mime,
                    asFile = asFile,
                    usePreferredPlayer = usePreferred,
                )
                is LocalHistoryTarget.WebDavStreamArchive -> OpenFileExternally.openWebDav(
                    context,
                    target.sourceId,
                    target.remotePath,
                    displayName = name,
                    mimeType = mime,
                    asFile = asFile,
                    usePreferredPlayer = usePreferred,
                )
                is LocalHistoryTarget.WebDavFile -> OpenFileExternally.openWebDav(
                    context,
                    target.sourceId,
                    target.remotePath,
                    displayName = name,
                    mimeType = mime,
                    asFile = asFile,
                    usePreferredPlayer = usePreferred,
                )
                is LocalHistoryTarget.LibraryGallery -> {
                    val local = LocalLibrary.loadGallery(target.galleryId) ?: return@io
                    OpenFileExternally.openLocal(
                        context,
                        local.contentPath,
                        displayName = name,
                        mimeType = mime,
                        asFile = asFile,
                        usePreferredPlayer = usePreferred,
                    )
                }
                else -> Unit
            }
        } catch (e: Throwable) {
            snackbar(
                context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
            )
        }
    }
}

private fun HistoryOverflowEnv.copyUrl(info: GalleryEntity) {
    val name = historyDisplayName(info)
    val mime = mimeTypeForFileName(name)
    io {
        try {
            val uri = when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalFile ->
                    if (isHtmlFileName(name)) {
                        OpenFileExternally.ensureLocalHtmlHttpUri(target.path, name, mime)
                    } else {
                        OpenFileExternally.ensureLocalVideoHttpUri(target.path, name, mime)
                    }
                is LocalHistoryTarget.SmbFile ->
                    if (isHtmlFileName(name)) {
                        OpenFileExternally.ensureSmbHtmlHttpUri(context, target.sourceId, target.remotePath, name, mime)
                    } else {
                        OpenFileExternally.ensureSmbVideoHttpUri(context, target.sourceId, target.remotePath, name, mime)
                    }
                is LocalHistoryTarget.WebDavFile ->
                    if (isHtmlFileName(name)) {
                        OpenFileExternally.ensureWebDavHtmlHttpUri(context, target.sourceId, target.remotePath, name, mime)
                    } else {
                        OpenFileExternally.ensureWebDavVideoHttpUri(context, target.sourceId, target.remotePath, name, mime)
                    }
                else -> return@io
            }
            withUIContext { with(context) { addTextToClipboard(uri.toString()) } }
        } catch (e: Throwable) {
            snackbar(
                context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
            )
        }
    }
}

private fun HistoryOverflowEnv.openHtml(info: GalleryEntity, incognito: Boolean) {
    val name = historyDisplayName(info)
    val mime = mimeTypeForFileName(name)
    io {
        try {
            when (val target = LocalHistory.parse(info)) {
                is LocalHistoryTarget.LocalFile -> OpenFileExternally.openLocalHtml(
                    context,
                    target.path,
                    name,
                    mime,
                    incognito,
                )
                is LocalHistoryTarget.SmbFile -> OpenFileExternally.openSmbHtml(
                    context,
                    target.sourceId,
                    target.remotePath,
                    name,
                    mime,
                    incognito,
                )
                is LocalHistoryTarget.WebDavFile -> OpenFileExternally.openWebDavHtml(
                    context,
                    target.sourceId,
                    target.remotePath,
                    name,
                    mime,
                    incognito,
                )
                else -> Unit
            }
        } catch (e: Throwable) {
            snackbar(
                context.getString(R.string.browse_open_failed) + " " + (e.message ?: e.toString()),
            )
        }
    }
}

private suspend fun localAbsPath(rootId: Long, relativePath: String) = LocalLibrary.loadRoot(rootId)?.let { root ->
    LocalLibrary.rootPath(root)?.let { base ->
        if (relativePath.isEmpty()) base else base.resolveRelative(relativePath)
    }
}
