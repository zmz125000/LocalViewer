package com.hippo.ehviewer.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.provider.requestStreamNotificationPermission
import java.io.File
import okio.Path.Companion.toPath

/**
 * Open a PDF in an external app (system / third-party reader).
 *
 * Local and SAF documents pass their real seekable descriptor through
 * [StreamDocumentProvider]. Network PDFs (including zip-as-dir members) use the
 * same origin-cache + transfer snackbar path as [OpenFileExternally].
 *
 * SAF tree document URIs (`content://…externalstorage…/tree/…/document/…`) are **not**
 * passed through: the grant lives on LocalViewer; chooser + Drive often cannot open them
 * (spaces in tree ids like `Quick Share` make it worse). We open the PFD ourselves and
 * re-export via streamdoc.
 *
 * Tap-to-open follows [Settings.pdfReaderMode]; call this for External / Open with.
 */
object OpenPdfExternally {
    fun isPdf(name: String): Boolean = isPdfFileName(name)

    /**
     * Local browse path (filesystem, SAF document, or MediaStore-style string).
     *
     * Browse stores SAF paths as okio [Path] strings. Okio collapses `content://` →
     * `content:/` (single slash), which [Uri.parse] treats as **no authority**
     * ("No content provider: content:/…"). Always open via [Path.openFileDescriptor],
     * which uses [com.ehviewer.core.files.toUri] to restore `content://` and rebuild
     * tree/document ids (spaces like `Quick Share`, multi-segment document paths).
     */
    suspend fun openLocal(
        context: Context,
        pathStr: String,
        displayName: String = File(pathStr).name,
        usePreferredReader: Boolean = true,
    ) {
        val openPfd: () -> ParcelFileDescriptor = {
            val file = File(pathStr)
            // Real absolute file only — do not treat content:/… as File.
            if (pathStr.startsWith('/') && file.isFile) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                pathStr.toPath().openFileDescriptor("r")
            }
        }
        val token = withIOContext {
            val sizeBytes = openPfd().use { pfd ->
                pfd.statSize.takeIf { it > 0L } ?: error("empty PDF")
            }
            StreamDocumentRegistry.registerDirect(
                displayName = displayName,
                mimeType = DefaultPdfReader.MIME_TYPE,
                sizeBytes = sizeBytes,
                openFileDescriptor = openPfd,
            )
        }
        launchRegistered(context, token, displayName, usePreferredReader = usePreferredReader)
    }

    private suspend fun launchRegistered(
        context: Context,
        token: String,
        displayName: String,
        networkStream: Boolean = false,
        usePreferredReader: Boolean = true,
    ) {
        val uri = StreamDocumentProvider.uriFor(token, displayName)
        try {
            if (networkStream) requestStreamNotificationPermission(context)
            DefaultPdfReader.startView(context, uri, displayName, usePreferredReader)
        } catch (e: Throwable) {
            StreamDocumentRegistry.remove(token)
            throw e
        }
    }

    suspend fun openSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        usePreferredReader: Boolean = true,
    ) {
        OpenFileExternally.openSmb(
            context = context,
            sourceId = sourceId,
            remoteRelativeFile = remoteRelativeFile,
            displayName = displayName,
            mimeType = DefaultPdfReader.MIME_TYPE,
            asFile = true,
            usePreferredPlayer = usePreferredReader,
        )
    }

    suspend fun openWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        usePreferredReader: Boolean = true,
    ) {
        OpenFileExternally.openWebDav(
            context = context,
            sourceId = sourceId,
            remoteRelativeFile = remoteRelativeFile,
            displayName = displayName,
            mimeType = DefaultPdfReader.MIME_TYPE,
            asFile = true,
            usePreferredPlayer = usePreferredReader,
        )
    }

    suspend fun openInternalLocal(
        context: Context,
        pathStr: String,
        displayName: String = File(pathStr).name,
    ) {
        OpenFileExternally.playPdfLocal(context, pathStr, displayName)
    }

    suspend fun openInternalSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
    ) {
        OpenFileExternally.playPdfSmb(context, sourceId, remoteRelativeFile, displayName)
    }

    suspend fun openInternalWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
    ) {
        OpenFileExternally.playPdfWebDav(context, sourceId, remoteRelativeFile, displayName)
    }
}
