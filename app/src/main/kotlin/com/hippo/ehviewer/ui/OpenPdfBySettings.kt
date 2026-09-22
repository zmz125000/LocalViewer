package com.hippo.ehviewer.ui

import android.content.Context
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import java.io.File

/**
 * Route a PDF [ReaderScreenArgs] (or browse path) through [Settings.pdfReaderMode].
 * Image-reader overflow / long-press pass [ReaderScreenArgs.skipPdfPrimary].
 */
object OpenPdfBySettings {
    fun shouldRedirect(args: ReaderScreenArgs): Boolean {
        if (args.skipPdfPrimary) return false
        if (!isPdfArgs(args)) return false
        return Settings.pdfReaderMode.value != PdfReaderMode.IMAGE
    }

    fun isPdfArgs(args: ReaderScreenArgs): Boolean = when (args) {
        is ReaderScreenArgs.Archive -> isPdfFileName(fileName(args.path))
        is ReaderScreenArgs.SmbStreamArchive -> isPdfFileName(fileName(args.remotePath))
        is ReaderScreenArgs.WebDavStreamArchive -> isPdfFileName(fileName(args.remotePath))
        else -> false
    }

    suspend fun open(context: Context, args: ReaderScreenArgs) {
        when (Settings.pdfReaderMode.value) {
            PdfReaderMode.PDF -> openInternal(context, args)
            PdfReaderMode.EXTERNAL -> openExternal(context, args)
            else -> error("image reader is not a PDF redirect")
        }
    }

    private suspend fun openInternal(context: Context, args: ReaderScreenArgs) {
        when (args) {
            is ReaderScreenArgs.Archive -> {
                val path = args.path
                val name = fileName(path)
                val info = args.info ?: LocalHistory.galleryInfoForLocalArchive(path, title = name)
                LocalHistory.ensureGalleryForProgress(info)
                LocalHistory.recordLocalArchive(path, title = name)
                OpenPdfExternally.openInternalLocal(
                    context,
                    path,
                    displayName = name,
                    progressGid = info.gid,
                    startPage = startPage(args.page, info.gid),
                )
            }
            is ReaderScreenArgs.SmbStreamArchive -> {
                val remote = args.remotePath.trim('/')
                val name = args.info?.title?.ifBlank { null } ?: fileName(remote)
                val info = args.info ?: smbInfo(args.sourceId, remote, name)
                LocalHistory.ensureGalleryForProgress(info)
                LocalHistory.recordSmbStreamArchive(
                    args.sourceId,
                    remote,
                    title = name,
                    info = info,
                )
                OpenPdfExternally.openInternalSmb(
                    context,
                    args.sourceId,
                    remote,
                    displayName = name,
                    progressGid = info.gid,
                    startPage = startPage(args.page, info.gid),
                )
            }
            is ReaderScreenArgs.WebDavStreamArchive -> {
                val remote = args.remotePath.trim('/')
                val name = args.info?.title?.ifBlank { null } ?: fileName(remote)
                val info = args.info ?: webDavInfo(args.sourceId, remote, name)
                LocalHistory.ensureGalleryForProgress(info)
                LocalHistory.recordWebDavStreamArchive(
                    args.sourceId,
                    remote,
                    title = name,
                    info = info,
                )
                OpenPdfExternally.openInternalWebDav(
                    context,
                    args.sourceId,
                    remote,
                    displayName = name,
                    progressGid = info.gid,
                    startPage = startPage(args.page, info.gid),
                )
            }
            else -> error("not a PDF archive")
        }
    }

    private suspend fun openExternal(context: Context, args: ReaderScreenArgs) {
        when (args) {
            is ReaderScreenArgs.Archive -> {
                val path = args.path
                val name = fileName(path)
                LocalHistory.recordLocalFile(path, title = name)
                OpenPdfExternally.openLocal(context, path, displayName = name)
            }
            is ReaderScreenArgs.SmbStreamArchive -> {
                val remote = args.remotePath.trim('/')
                val name = args.info?.title?.ifBlank { null } ?: fileName(remote)
                LocalHistory.recordSmbFile(args.sourceId, remote, title = name)
                OpenPdfExternally.openSmb(context, args.sourceId, remote, displayName = name)
            }
            is ReaderScreenArgs.WebDavStreamArchive -> {
                val remote = args.remotePath.trim('/')
                val name = args.info?.title?.ifBlank { null } ?: fileName(remote)
                LocalHistory.recordWebDavFile(args.sourceId, remote, title = name)
                OpenPdfExternally.openWebDav(context, args.sourceId, remote, displayName = name)
            }
            else -> error("not a PDF archive")
        }
    }

    private suspend fun startPage(requested: Int, gid: Long): Int {
        if (requested >= 0) return requested
        return runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0)
    }

    private fun fileName(path: String): String = path.trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifEmpty { File(path).name }

    private fun smbInfo(sourceId: Long, remote: String, name: String) = BaseGalleryInfo(
        gid = stableGalleryId(sourceId, "smba:$remote"),
        token = SMB_ARCHIVE_TOKEN,
        title = name,
        pages = 0,
        favoriteSlot = NOT_FAVORITED,
        rating = -1f,
        thumbKey = HistoryThumbKey.smbArchive(sourceId, remote),
        uploader = "$sourceId\u0000$remote",
        category = 1,
    )

    private fun webDavInfo(sourceId: Long, remote: String, name: String) = BaseGalleryInfo(
        gid = stableGalleryId(sourceId, "dava:$remote"),
        token = WEBDAV_ARCHIVE_TOKEN,
        title = name,
        pages = 0,
        favoriteSlot = NOT_FAVORITED,
        rating = -1f,
        thumbKey = HistoryThumbKey.webdavArchive(sourceId, remote),
        uploader = "$sourceId\u0000$remote",
        category = 1,
    )
}
