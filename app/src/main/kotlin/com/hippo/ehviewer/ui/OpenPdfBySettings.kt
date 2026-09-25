package com.hippo.ehviewer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.document.PdfContentKind
import com.hippo.ehviewer.library.document.PdfImageEngine
import com.hippo.ehviewer.library.isEbookFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.openLocalArchiveByteSource
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.reader.PendingReaderOpen
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * Route a PDF [ReaderScreenArgs] (or browse path) through [Settings.pdfReaderMode].
 * Image-reader overflow / long-press pass [ReaderScreenArgs.skipPdfPrimary].
 */
object OpenPdfBySettings {
    /** PDF/external activity started, or image PDF handed to the gallery reader. */
    sealed interface Outcome {
        data object Handled : Outcome
        data class Gallery(val args: ReaderScreenArgs) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun shouldRedirect(args: ReaderScreenArgs): Boolean {
        if (args.skipPdfPrimary) return false
        if (isEbookArgs(args)) return true
        if (!isPdfArgs(args)) return false
        return Settings.pdfReaderMode.value != PdfReaderMode.IMAGE
    }

    /** Stay in [PdfReaderActivity] (ebook, or PDF that is not image/external). */
    suspend fun shouldOpenInternal(args: ReaderScreenArgs): Boolean {
        if (args.skipPdfPrimary) return false
        if (isEbookArgs(args)) return true
        if (!isPdfArgs(args)) return false
        return when (Settings.pdfReaderMode.value) {
            PdfReaderMode.PDF -> true
            PdfReaderMode.AUTO -> !isImagePdf(args)
            else -> false
        }
    }

    /** Open PDF/external without composing [com.hippo.ehviewer.ui.reader.ReaderScreen]. */
    fun launch(context: Context, args: ReaderScreenArgs) {
        scope.launch {
            runCatching {
                when (val outcome = open(context, args)) {
                    is Outcome.Gallery -> handoffGallery(context, outcome.args)
                    Outcome.Handled -> Unit
                }
            }.onFailure { e ->
                logcat("OpenPdfBySettings", e)
                withUIContext {
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.pdf_reader_open_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** Bring [MainActivity] forward with [args] (skip PDF redirect). */
    fun handoffGallery(context: Context, args: ReaderScreenArgs) {
        PendingReaderOpen.offer(args)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = PendingReaderOpen.ACTION
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isPdfArgs(args: ReaderScreenArgs): Boolean = when (args) {
        is ReaderScreenArgs.Archive -> isPdfFileName(fileName(args.path))
        is ReaderScreenArgs.SmbStreamArchive -> isPdfFileName(fileName(args.remotePath))
        is ReaderScreenArgs.WebDavStreamArchive -> isPdfFileName(fileName(args.remotePath))
        else -> false
    }

    fun isEbookArgs(args: ReaderScreenArgs): Boolean = when (args) {
        is ReaderScreenArgs.Archive -> isEbookFileName(fileName(args.path))
        is ReaderScreenArgs.SmbStreamArchive -> isEbookFileName(fileName(args.remotePath))
        is ReaderScreenArgs.WebDavStreamArchive -> isEbookFileName(fileName(args.remotePath))
        else -> false
    }

    /**
     * Long-press while Auto is selected: the built-in reader tap would not have used.
     */
    fun launchOtherBuiltin(context: Context, args: ReaderScreenArgs) {
        scope.launch {
            runCatching {
                if (isImagePdf(args)) {
                    openInternal(context, args)
                } else {
                    handoffGallery(context, args.asGallery())
                }
            }.onFailure { e ->
                logcat("OpenPdfBySettings", e)
                withUIContext {
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.pdf_reader_open_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    suspend fun open(context: Context, args: ReaderScreenArgs): Outcome {
        if (isEbookArgs(args)) {
            openInternal(context, args)
            return Outcome.Handled
        }
        when (Settings.pdfReaderMode.value) {
            PdfReaderMode.AUTO -> {
                if (isImagePdf(args)) return Outcome.Gallery(args.asGallery())
                openInternal(context, args)
            }
            PdfReaderMode.PDF -> openInternal(context, args)
            PdfReaderMode.EXTERNAL -> openExternal(context, args)
            else -> error("image reader is not a PDF redirect")
        }
        return Outcome.Handled
    }

    /** Front-page sample. Failures stay on the PDF reader. */
    private suspend fun isImagePdf(args: ReaderScreenArgs): Boolean = withIOContext {
        val source = openClassifySource(args) ?: return@withIOContext false
        try {
            PdfImageEngine.classify(source, source.size) == PdfContentKind.Image
        } catch (e: Throwable) {
            logcat("OpenPdfBySettings", e)
            false
        } finally {
            runCatching { source.close() }
        }
    }

    private suspend fun openClassifySource(args: ReaderScreenArgs): ArchiveByteSource? = when (args) {
        is ReaderScreenArgs.Archive -> openLocalArchiveByteSource(args.path.toPath())
        is ReaderScreenArgs.SmbStreamArchive -> {
            val entity = SmbRepository.load(args.sourceId) ?: return null
            SmbArchiveByteSource(
                entity,
                SmbPasswordStore.get(entity.id),
                args.remotePath,
                preferSequential = false,
                pipeline = false,
            )
        }
        is ReaderScreenArgs.WebDavStreamArchive -> {
            val entity = WebDavRepository.load(args.sourceId) ?: return null
            WebDavArchiveByteSource(
                entity,
                WebDavPasswordStore.get(entity.id),
                args.remotePath,
                preferSequential = false,
                pipeline = false,
            )
        }
        else -> null
    }

    private fun ReaderScreenArgs.asGallery(): ReaderScreenArgs = when (this) {
        is ReaderScreenArgs.Archive -> copy(skipPdfPrimary = true)
        is ReaderScreenArgs.SmbStreamArchive -> copy(skipPdfPrimary = true)
        is ReaderScreenArgs.WebDavStreamArchive -> copy(skipPdfPrimary = true)
        else -> this
    }

    private suspend fun openInternal(context: Context, args: ReaderScreenArgs) {
        val intent = prepareInternal(context, args)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        withUIContext { context.startActivity(intent) }
    }

    /**
     * History + streamdoc [Intent] for [PdfReaderActivity] without starting it.
     * Sibling hop reuses the same activity instead of flashing a loading spinner.
     */
    suspend fun prepareInternal(context: Context, args: ReaderScreenArgs): Intent = when (args) {
        is ReaderScreenArgs.Archive -> {
            val path = args.path
            val name = fileName(path)
            val info = args.info ?: LocalHistory.galleryInfoForLocalArchive(path, title = name)
            LocalHistory.ensureGalleryForProgress(info)
            LocalHistory.recordLocalArchive(path, title = name)
            OpenFileExternally.preparePdfReaderIntentLocal(
                context,
                path,
                name,
                info.gid,
                startPage(args.page, info.gid),
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
            OpenFileExternally.preparePdfReaderIntentSmb(
                context,
                args.sourceId,
                remote,
                name,
                info.gid,
                startPage(args.page, info.gid),
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
            OpenFileExternally.preparePdfReaderIntentWebDav(
                context,
                args.sourceId,
                remote,
                name,
                info.gid,
                startPage(args.page, info.gid),
            )
        }
        else -> error("not a PDF archive")
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

    private fun fileName(path: String): String = ZipPaths.memberLeafName(path)
        ?: path.trimEnd('/', '\\')
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
