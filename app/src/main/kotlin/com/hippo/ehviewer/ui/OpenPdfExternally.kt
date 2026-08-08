package com.hippo.ehviewer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.library.FileArchiveByteSource
import com.hippo.ehviewer.library.PfdArchiveByteSource
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.io.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * Open a PDF in an external app (system / third-party reader).
 *
 * Local + network use a grantable [StreamDocumentProvider] URI backed by
 * [com.hippo.ehviewer.library.ArchiveByteSource] range I/O — **no full download**
 * for SMB/WebDAV when the viewer seeks. SAF `content://` paths are passed through.
 *
 * Tap-to-open in the in-app image PDF engine is unchanged; call this from long-press.
 */
object OpenPdfExternally {
    fun isPdf(name: String): Boolean = isPdfFileName(name)

    /**
     * Local browse path (filesystem, SAF document, or MediaStore-style string).
     */
    suspend fun openLocal(context: Context, pathStr: String, displayName: String = File(pathStr).name) {
        if (pathStr.startsWith("content:")) {
            launchView(context, pathStr.toUri(), displayName)
            return
        }
        val file = File(pathStr)
        if (file.isFile) {
            openStreaming(context, displayName) { FileArchiveByteSource(file) }
            return
        }
        // SAF / other path types: open via PFD like the in-app document reader.
        val path: Path = pathStr.toPath()
        openStreaming(context, displayName) {
            val pfd = path.openFileDescriptor("r")
            PfdArchiveByteSource(pfd, ownsPfd = true)
        }
    }

    suspend fun openSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
    ) {
        val source = withIOContext {
            SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
        }
        val password = SmbPasswordStore.get(sourceId)
        openStreaming(context, displayName) {
            // External PDF viewers seek randomly (xref / page objects). Pipeline + 8 MiB
            // sequential windows thrash the single SMB handle and surface as Fuse EIO spam.
            // stickySession: dedicated TCP outside the browse/reader pool so ON_STOP
            // (user switched to Drive) does not kill the FUSE stream mid-read.
            SmbArchiveByteSource(
                source = source,
                password = password,
                remoteRelativeFile = remoteRelativeFile,
                preferSequential = false,
                pipeline = false,
                sequentialWindow = EXTERNAL_PDF_WINDOW,
                stickySession = true,
            )
        }
    }

    suspend fun openWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
    ) {
        val source = withIOContext {
            WebDavRepository.load(sourceId) ?: throw IOException("WebDAV source missing")
        }
        val password = WebDavPasswordStore.get(sourceId)
        openStreaming(context, displayName) {
            // stickySession: separate CIO client that survives ON_STOP when Drive is foreground.
            WebDavArchiveByteSource(
                source = source,
                password = password,
                remoteRelativeFile = remoteRelativeFile,
                preferSequential = false,
                pipeline = false,
                sequentialWindow = EXTERNAL_PDF_WINDOW,
                stickySession = true,
            )
        }
    }

    private suspend fun openStreaming(
        context: Context,
        displayName: String,
        openSource: () -> com.hippo.ehviewer.library.ArchiveByteSource,
    ) {
        val token = withIOContext {
            // Fail fast if we cannot open/size (clearer snackbar than a dead chooser).
            // Publish size so external apps (Drive) can stop at EOF without probing past end.
            val sizeBytes = openSource().use { src ->
                val n = src.size
                if (n < 1L) error("empty PDF")
                n
            }
            StreamDocumentRegistry.register(
                displayName = displayName,
                mimeType = "application/pdf",
                sizeBytes = sizeBytes,
                openSource = openSource,
            )
        }
        val uri = StreamDocumentProvider.uriFor(token)
        try {
            launchView(context, uri, displayName)
        } catch (e: Throwable) {
            StreamDocumentRegistry.remove(token)
            throw e
        }
    }

    /**
     * Window for external viewers: enough for Fuse page-sized reads, small enough that a
     * jump to page N does not pull multi‑MiB dead weight on the keep-open SMB/WebDAV handle.
     */
    private const val EXTERNAL_PDF_WINDOW = 256 * 1024

    private suspend fun launchView(context: Context, uri: Uri, displayName: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Chooser may run in a new task when not started from an Activity base.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val title = context.getString(R.string.open_in_other_app)
        val chooser = Intent.createChooser(view, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withUIContext {
            try {
                context.startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                logcat("OpenPdfExternally", e)
                error(context.getString(R.string.open_pdf_no_app))
            }
        }
    }
}
