package com.hippo.ehviewer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.io.IOException
import okio.Path.Companion.toPath

/**
 * Open a PDF in an external app (system / third-party reader).
 *
 * Local + network always use a grantable [StreamDocumentProvider] URI. Local and SAF
 * documents pass their real seekable descriptor through the provider; SMB/WebDAV use
 * range I/O with a bounded sparse block cache — **no full download** when the viewer seeks.
 *
 * SAF tree document URIs (`content://…externalstorage…/tree/…/document/…`) are **not**
 * passed through: the grant lives on LocalViewer; chooser + Drive often cannot open them
 * (spaces in tree ids like `Quick Share` make it worse). We open the PFD ourselves and
 * re-export via streamdoc.
 *
 * Tap-to-open in the in-app image PDF engine is unchanged; call this from long-press.
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
    suspend fun openLocal(context: Context, pathStr: String, displayName: String = File(pathStr).name) {
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
                mimeType = "application/pdf",
                sizeBytes = sizeBytes,
                openFileDescriptor = openPfd,
            )
        }
        launchRegistered(context, token, displayName)
    }

    private suspend fun launchRegistered(context: Context, token: String, displayName: String) {
        val uri = StreamDocumentProvider.uriFor(token)
        try {
            launchView(context, uri, displayName)
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
    ) {
        val source = withIOContext {
            SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
        }
        val password = SmbPasswordStore.get(sourceId)
        // Cheap size probe (open+stat+close on the browse pool) — do not spin a sticky
        // keep-open ArchiveByteSource only to throw it away before the viewer attaches.
        val sizeBytes = withIOContext {
            SmbGateway.fileSizeOrNull(source, password, remoteRelativeFile)
                ?.takeIf { it > 0L }
                ?: error("empty or unreachable PDF")
        }
        val token = StreamDocumentRegistry.register(
            displayName = displayName,
            mimeType = "application/pdf",
            sizeBytes = sizeBytes,
            openSource = {
                // stickySession: dedicated TCP outside the browse/reader pool so ON_STOP
                // (user switched to Drive) does not kill the FUSE stream mid-read.
                // readahead off: BlockCacheArchiveByteSource owns multi-region caching.
                // knownSize: no second size open on first Fuse read.
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remoteRelativeFile,
                    preferSequential = false,
                    pipeline = false,
                    stickySession = true,
                    knownSize = sizeBytes,
                    readahead = false,
                )
            },
        )
        launchRegistered(context, token, displayName)
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
        // One sticky HEAD (or 0–0 Range) for size; seed knownSize so first readAt never re-HEADs.
        val sizeBytes = withIOContext {
            WebDavClient.fileSizeOrNull(
                source,
                password,
                remoteRelativeFile,
                sticky = true,
            )?.takeIf { it > 0L } ?: error("empty or unreachable PDF")
        }
        val token = StreamDocumentRegistry.register(
            displayName = displayName,
            mimeType = "application/pdf",
            sizeBytes = sizeBytes,
            openSource = {
                WebDavArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remoteRelativeFile,
                    preferSequential = false,
                    pipeline = false,
                    stickySession = true,
                    knownSize = sizeBytes,
                    readahead = false,
                )
            },
        )
        launchRegistered(context, token, displayName)
    }

    private suspend fun launchView(context: Context, uri: Uri, displayName: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Chooser may run in a new task when not started from an Activity base.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Without ClipData, FLAG_GRANT_READ_URI_PERMISSION is often ignored for the
            // app the user picks in createChooser (streamdoc grant would not reach Drive).
            clipData = ClipData.newRawUri(displayName, uri)
        }
        val title = context.getString(R.string.open_in_other_app)
        val chooser = Intent.createChooser(view, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
