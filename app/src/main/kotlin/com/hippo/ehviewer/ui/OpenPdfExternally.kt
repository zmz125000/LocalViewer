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
            SmbArchiveByteSource(
                source = source,
                password = password,
                remoteRelativeFile = remoteRelativeFile,
                preferSequential = false,
                pipeline = true,
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
            WebDavArchiveByteSource(
                source = source,
                password = password,
                remoteRelativeFile = remoteRelativeFile,
                preferSequential = false,
                pipeline = true,
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
            openSource().use { src ->
                if (src.size < 1L) error("empty PDF")
            }
            StreamDocumentRegistry.register(
                displayName = displayName,
                mimeType = "application/pdf",
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
