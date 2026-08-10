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
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.provider.requestStreamNotificationPermission
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
 * Open a non-gallery file (video, regular file, etc.) via [StreamDocumentProvider].
 *
 * - [openLocal]/[openSmb]/[openWebDav] — external app chooser (long-press video).
 * - [playLocal]/[playSmb]/[playWebDav] — in-app Media3 player (tap video).
 */
object OpenFileExternally {
    suspend fun openLocal(
        context: Context,
        pathStr: String,
        displayName: String = File(pathStr).name,
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerLocal(pathStr, displayName, mimeType)
        launchRegistered(context, token, displayName, mimeType, networkStream = false, internalPlayer = false)
    }

    suspend fun playLocal(
        context: Context,
        pathStr: String,
        displayName: String = File(pathStr).name,
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerLocal(pathStr, displayName, mimeType)
        launchRegistered(context, token, displayName, mimeType, networkStream = false, internalPlayer = true)
    }

    suspend fun openSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerSmb(sourceId, remoteRelativeFile, displayName, mimeType)
        launchRegistered(context, token, displayName, mimeType, networkStream = true, internalPlayer = false)
    }

    suspend fun playSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerSmb(sourceId, remoteRelativeFile, displayName, mimeType)
        launchRegistered(context, token, displayName, mimeType, networkStream = true, internalPlayer = true)
    }

    suspend fun openWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerWebDav(sourceId, remoteRelativeFile, displayName, mimeType)
        launchRegistered(context, token, displayName, mimeType, networkStream = true, internalPlayer = false)
    }

    suspend fun playWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerWebDav(sourceId, remoteRelativeFile, displayName, mimeType)
        launchRegistered(context, token, displayName, mimeType, networkStream = true, internalPlayer = true)
    }

    private suspend fun registerLocal(
        pathStr: String,
        displayName: String,
        mimeType: String,
    ): String {
        val openPfd: () -> ParcelFileDescriptor = {
            val file = File(pathStr)
            if (pathStr.startsWith('/') && file.isFile) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                pathStr.toPath().openFileDescriptor("r")
            }
        }
        return withIOContext {
            val sizeBytes = openPfd().use { pfd ->
                pfd.statSize.takeIf { it > 0L } ?: error("empty file")
            }
            StreamDocumentRegistry.registerDirect(
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                openFileDescriptor = openPfd,
            )
        }
    }

    private suspend fun registerSmb(
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
    ): String {
        val source = withIOContext {
            SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
        }
        val password = SmbPasswordStore.get(sourceId)
        val sizeBytes = withIOContext {
            SmbGateway.fileSizeOrNull(source, password, remoteRelativeFile)
                ?.takeIf { it > 0L }
                ?: error("empty or unreachable file")
        }
        return StreamDocumentRegistry.register(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            // Each SmbArchiveByteSource(stickySession=true) owns an independent TCP session,
            // so video dual-lane prefetch may open a second demand-free lane safely.
            parallelPrefetch = true,
            openSource = {
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remoteRelativeFile,
                    preferSequential = false,
                    pipeline = false,
                    stickySession = true,
                    knownSize = sizeBytes,
                    // Windowing/prefetch owned by VideoDirectLinkByteSource for video.
                    readahead = false,
                )
            },
        )
    }

    private suspend fun registerWebDav(
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
    ): String {
        val source = withIOContext {
            WebDavRepository.load(sourceId) ?: throw IOException("WebDAV source missing")
        }
        val password = WebDavPasswordStore.get(sourceId)
        val sizeBytes = withIOContext {
            WebDavClient.fileSizeOrNull(
                source,
                password,
                remoteRelativeFile,
                sticky = true,
            )?.takeIf { it > 0L } ?: error("empty or unreachable file")
        }
        return StreamDocumentRegistry.register(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            parallelPrefetch = true,
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
    }

    private suspend fun launchRegistered(
        context: Context,
        token: String,
        displayName: String,
        mimeType: String,
        networkStream: Boolean,
        internalPlayer: Boolean,
    ) {
        val uri = StreamDocumentProvider.uriFor(token)
        try {
            if (networkStream) requestStreamNotificationPermission(context)
            if (internalPlayer) {
                launchInternalPlayer(context, uri, displayName, mimeType, token)
            } else {
                launchView(context, uri, displayName, mimeType)
            }
        } catch (e: Throwable) {
            StreamDocumentRegistry.remove(token)
            throw e
        }
    }

    private suspend fun launchInternalPlayer(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        token: String,
    ) {
        val intent = VideoPlayerActivity.intent(
            context = context,
            uri = uri,
            title = displayName,
            mimeType = mimeType,
            streamToken = token,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withUIContext {
            context.startActivity(intent)
        }
    }

    private suspend fun launchView(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
    ) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                logcat("OpenFileExternally", e)
                error(context.getString(R.string.browse_open_failed))
            }
        }
    }
}
