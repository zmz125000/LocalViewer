package com.hippo.ehviewer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.SidecarSubtitles
import com.hippo.ehviewer.library.isBrowseVideoFileName
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.provider.ExternalHttpStreamServer
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.provider.requestStreamNotificationPermission
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

/**
 * Open a non-gallery file (video, regular file, etc.).
 *
 * - **External video** → loopback [ExternalHttpStreamServer]
 *   (`http://127.0.0.1/…/movie.webm`) so players auto-load sibling subs like SMB explorers.
 * - **External non-video** → [StreamDocumentProvider] content URI.
 * - **In-app Media3** ([playLocal]/[playSmb]/[playWebDav]) → streamdoc / StreamDocDataSource.
 */
object OpenFileExternally {
    suspend fun openLocal(
        context: Context,
        pathStr: String,
        displayName: String = File(pathStr).name,
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        if (DefaultVideoPlayer.isVideoMime(mimeType)) {
            openLocalVideoHttp(context, pathStr, displayName, mimeType)
            return
        }
        val token = registerLocalStreamdoc(pathStr, displayName, mimeType)
        launchStreamdoc(
            context = context,
            token = token,
            displayName = displayName,
            mimeType = mimeType,
            networkStream = false,
            internalPlayer = false,
        )
    }

    suspend fun playLocal(
        context: Context,
        pathStr: String,
        displayName: String = File(pathStr).name,
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerLocalStreamdoc(pathStr, displayName, mimeType)
        launchStreamdoc(context, token, displayName, mimeType, networkStream = false, internalPlayer = true)
    }

    suspend fun openSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        if (DefaultVideoPlayer.isVideoMime(mimeType)) {
            openSmbVideoHttp(context, sourceId, remoteRelativeFile, displayName, mimeType)
            return
        }
        val token = registerSmbStreamdoc(sourceId, remoteRelativeFile, displayName, mimeType)
        launchStreamdoc(
            context = context,
            token = token,
            displayName = displayName,
            mimeType = mimeType,
            networkStream = true,
            internalPlayer = false,
        )
    }

    suspend fun playSmb(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerSmbStreamdoc(sourceId, remoteRelativeFile, displayName, mimeType)
        launchStreamdoc(context, token, displayName, mimeType, networkStream = true, internalPlayer = true)
    }

    suspend fun openWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        if (DefaultVideoPlayer.isVideoMime(mimeType)) {
            openWebDavVideoHttp(context, sourceId, remoteRelativeFile, displayName, mimeType)
            return
        }
        val token = registerWebDavStreamdoc(sourceId, remoteRelativeFile, displayName, mimeType)
        launchStreamdoc(
            context = context,
            token = token,
            displayName = displayName,
            mimeType = mimeType,
            networkStream = true,
            internalPlayer = false,
        )
    }

    suspend fun playWebDav(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\'),
        mimeType: String = mimeTypeForFileName(displayName),
    ) {
        val token = registerWebDavStreamdoc(sourceId, remoteRelativeFile, displayName, mimeType)
        launchStreamdoc(context, token, displayName, mimeType, networkStream = true, internalPlayer = true)
    }

    // region HTTP external video

    /**
     * Off (default): opened video + matching sidecars only.
     * On: every video + subtitle file in the same directory (folder playlist).
     */
    private fun accessDirEnabled(): Boolean = Settings.externalVideoAccessDir.value

    /**
     * When [accessDirEnabled] and this are on: intent data is a generated m3u8 of folder
     * videos (current first) instead of the single file URI + multi-file extras.
     */
    private fun passFolderPlaylistEnabled(): Boolean =
        accessDirEnabled() && Settings.externalVideoPassFolderPlaylist.value

    private fun isHttpExposedMediaName(name: String): Boolean = isBrowseVideoFileName(name) || SidecarSubtitles.isSubtitleFileName(name)

    private fun mediaMimeForName(name: String): String = when {
        SidecarSubtitles.isSubtitleFileName(name) -> SidecarSubtitles.mimeTypeForSubtitle(name)
        else -> mimeTypeForFileName(name)
    }

    private fun canResolveSibling(videoName: String, candidateName: String, accessDir: Boolean): Boolean {
        if (!ExternalHttpStreamServer.isSafeFileName(candidateName)) return false
        return if (accessDir) {
            isHttpExposedMediaName(candidateName)
        } else {
            SidecarSubtitles.isSidecarFor(videoName, candidateName)
        }
    }

    private suspend fun buildHttpSession(
        network: Boolean,
        configure: suspend (ExternalHttpStreamServer.Session) -> Unit,
    ): ExternalHttpStreamServer.Session {
        val session = ExternalHttpStreamServer.newSession(network)
        return try {
            configure(session)
            session
        } catch (e: Throwable) {
            ExternalHttpStreamServer.removeSession(session.id)
            throw e
        }
    }

    private suspend fun openLocalVideoHttp(
        context: Context,
        pathStr: String,
        displayName: String,
        mimeType: String,
    ) {
        val accessDir = accessDirEnabled()
        val session = withIOContext {
            buildHttpSession(network = false) { session ->
                session.put(localFileEntry(pathStr, displayName, mimeType))
                session.resolveSibling = resolve@{ name ->
                    if (!canResolveSibling(displayName, name, accessDir)) return@resolve null
                    val subPath = siblingPath(pathStr, name) ?: return@resolve null
                    runCatching {
                        localFileEntry(subPath, name, mediaMimeForName(name))
                    }.getOrNull()
                }
                val extras = if (accessDir) {
                    listLocalDirMediaNames(pathStr).filterNot { it.equals(displayName, ignoreCase = true) }
                } else {
                    findLocalSidecarNames(pathStr, displayName)
                }
                for (name in extras) {
                    val subPath = siblingPath(pathStr, name) ?: continue
                    runCatching {
                        session.put(localFileEntry(subPath, name, mediaMimeForName(name)))
                    }.onFailure { logcat("OpenFileExternally", it) }
                }
            }
        }
        val videoUri = ExternalHttpStreamServer.uriFor(session.id, displayName)
        logcat("OpenFileExternally") {
            "HTTP local video $videoUri files=${session.files.size} accessDir=$accessDir"
        }
        try {
            launchHttpView(context, videoUri, displayName, mimeType, session, accessDir)
        } catch (e: Throwable) {
            ExternalHttpStreamServer.removeSession(session.id)
            throw e
        }
    }

    private suspend fun openSmbVideoHttp(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
    ) {
        requestStreamNotificationPermission(context)
        val accessDir = accessDirEnabled()
        val session = withIOContext {
            val source = SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
            val password = SmbPasswordStore.get(sourceId)
            val sizeBytes = SmbGateway.fileSizeOrNull(source, password, remoteRelativeFile)
                ?.takeIf { it > 0L }
                ?: error("empty or unreachable file")
            val parentDir = parentRelative(remoteRelativeFile)
            buildHttpSession(network = true) { session ->
                session.put(
                    smbFileEntry(source, password, remoteRelativeFile, displayName, mimeType, sizeBytes),
                )
                session.resolveSibling = { name ->
                    // HTTP worker thread — blocking size probe is intentional.
                    runBlocking {
                        if (!canResolveSibling(displayName, name, accessDir)) return@runBlocking null
                        val remote = SmbGateway.joinRelativePath(parentDir, name)
                        val size = SmbGateway.fileSizeOrNull(source, password, remote)
                            ?.takeIf { it > 0L }
                            ?: return@runBlocking null
                        smbFileEntry(source, password, remote, name, mediaMimeForName(name), size)
                    }
                }
                val extras = if (accessDir) {
                    listSmbDirMediaNames(sourceId, source, password, parentDir)
                        .filterNot { it.equals(displayName, ignoreCase = true) }
                } else {
                    findSmbSidecarNames(sourceId, source, password, remoteRelativeFile, displayName)
                }
                for (name in extras) {
                    val remote = SmbGateway.joinRelativePath(parentDir, name)
                    runCatching {
                        val size = SmbGateway.fileSizeOrNull(source, password, remote)
                            ?.takeIf { it > 0L }
                            ?: return@runCatching
                        session.put(
                            smbFileEntry(source, password, remote, name, mediaMimeForName(name), size),
                        )
                    }.onFailure { logcat("OpenFileExternally", it) }
                }
                logcat("OpenFileExternally") {
                    "HTTP SMB session ${session.id} files=${session.files.size} accessDir=$accessDir"
                }
            }
        }
        val videoUri = ExternalHttpStreamServer.uriFor(session.id, displayName)
        try {
            launchHttpView(context, videoUri, displayName, mimeType, session, accessDir)
        } catch (e: Throwable) {
            ExternalHttpStreamServer.removeSession(session.id)
            throw e
        }
    }

    private suspend fun openWebDavVideoHttp(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
    ) {
        requestStreamNotificationPermission(context)
        val accessDir = accessDirEnabled()
        val session = withIOContext {
            val source = WebDavRepository.load(sourceId) ?: throw IOException("WebDAV source missing")
            val password = WebDavPasswordStore.get(sourceId)
            val sizeBytes = WebDavClient.fileSizeOrNull(source, password, remoteRelativeFile, sticky = true)
                ?.takeIf { it > 0L }
                ?: error("empty or unreachable file")
            val parentDir = parentRelative(remoteRelativeFile)
            buildHttpSession(network = true) { session ->
                session.put(
                    webDavFileEntry(source, password, remoteRelativeFile, displayName, mimeType, sizeBytes),
                )
                session.resolveSibling = { name ->
                    runBlocking {
                        if (!canResolveSibling(displayName, name, accessDir)) return@runBlocking null
                        val remote = WebDavGateway.joinRelative(parentDir, name)
                        val size = WebDavClient.fileSizeOrNull(source, password, remote, sticky = true)
                            ?.takeIf { it > 0L }
                            ?: return@runBlocking null
                        webDavFileEntry(source, password, remote, name, mediaMimeForName(name), size)
                    }
                }
                val extras = if (accessDir) {
                    listWebDavDirMediaNames(sourceId, source, password, parentDir)
                        .filterNot { it.equals(displayName, ignoreCase = true) }
                } else {
                    findWebDavSidecarNames(sourceId, source, password, remoteRelativeFile, displayName)
                }
                for (name in extras) {
                    val remote = WebDavGateway.joinRelative(parentDir, name)
                    runCatching {
                        val size = WebDavClient.fileSizeOrNull(source, password, remote, sticky = true)
                            ?.takeIf { it > 0L }
                            ?: return@runCatching
                        session.put(
                            webDavFileEntry(source, password, remote, name, mediaMimeForName(name), size),
                        )
                    }.onFailure { logcat("OpenFileExternally", it) }
                }
            }
        }
        val videoUri = ExternalHttpStreamServer.uriFor(session.id, displayName)
        try {
            launchHttpView(context, videoUri, displayName, mimeType, session, accessDir)
        } catch (e: Throwable) {
            ExternalHttpStreamServer.removeSession(session.id)
            throw e
        }
    }

    private fun localFileEntry(
        pathStr: String,
        displayName: String,
        mimeType: String,
    ): ExternalHttpStreamServer.FileEntry {
        val file = File(pathStr)
        if (pathStr.startsWith('/') && file.isFile) {
            val size = file.length().takeIf { it > 0L } ?: error("empty file")
            return ExternalHttpStreamServer.FileEntry(
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = size,
                open = { ExternalHttpStreamServer.LocalFileBody(file) },
            )
        }
        val openPfd: () -> ParcelFileDescriptor = { pathStr.toPath().openFileDescriptor("r") }
        val size = openPfd().use { it.statSize.takeIf { s -> s > 0L } ?: error("empty file") }
        return ExternalHttpStreamServer.FileEntry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = size,
            open = {
                ExternalHttpStreamServer.PfdBody(openPfd())
            },
        )
    }

    private fun smbFileEntry(
        source: SmbSourceEntity,
        password: String,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ) = ExternalHttpStreamServer.FileEntry(
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        open = {
            ExternalHttpStreamServer.ArchiveBody(
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remoteRelativeFile,
                    preferSequential = false,
                    pipeline = false,
                    stickySession = true,
                    knownSize = sizeBytes,
                    readahead = false,
                ),
            )
        },
    )

    private fun webDavFileEntry(
        source: WebDavSourceEntity,
        password: String,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ) = ExternalHttpStreamServer.FileEntry(
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        open = {
            ExternalHttpStreamServer.ArchiveBody(
                WebDavArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remoteRelativeFile,
                    preferSequential = false,
                    pipeline = false,
                    stickySession = true,
                    knownSize = sizeBytes,
                    readahead = false,
                ),
            )
        },
    )

    private fun findLocalSidecarNames(videoPathStr: String, videoName: String): List<String> {
        val siblings = findLocalSiblingNames(videoPathStr).ifEmpty {
            // SAF/Okio-backed paths may support sibling opens without a listable java.io parent.
            SidecarSubtitles.probeCandidateNames(videoName).filter { name ->
                val path = siblingPath(videoPathStr, name) ?: return@filter false
                runCatching { localFileEntry(path, name, mediaMimeForName(name)) }.isSuccess
            }
        }
        return SidecarSubtitles.matchSiblings(
            videoName,
            siblings.filter(ExternalHttpStreamServer::isSafeFileName),
        )
    }

    /** All browse videos + subtitle files in the local parent directory. */
    private fun listLocalDirMediaNames(videoPathStr: String): List<String> = findLocalSiblingNames(videoPathStr)
        .filter { ExternalHttpStreamServer.isSafeFileName(it) && isHttpExposedMediaName(it) }
        .distinct()
        .sorted()
        .take(MAX_DIR_MEDIA_FILES)

    private suspend fun findSmbSidecarNames(
        sourceId: Long,
        source: SmbSourceEntity,
        password: String,
        remoteRelativeFile: String,
        videoName: String,
    ): List<String> {
        val parentDir = parentRelative(remoteRelativeFile)
        val names = siblingNamesFromCache(sourceId, parentDir, smb = true)
            .ifEmpty {
                SidecarSubtitles.probeCandidateNames(videoName).filter { name ->
                    val remote = SmbGateway.joinRelativePath(parentDir, name)
                    SmbGateway.fileSizeOrNull(source, password, remote)?.let { it > 0L } == true
                }
            }
        return SidecarSubtitles.matchSiblings(videoName, names)
    }

    private suspend fun listSmbDirMediaNames(
        sourceId: Long,
        source: SmbSourceEntity,
        password: String,
        parentDir: String,
    ): List<String> {
        val names = siblingNamesFromCache(sourceId, parentDir, smb = true)
            .ifEmpty {
                runCatching {
                    SmbGateway.listDirectory(source, password, parentDir, useCache = true)
                        .mapNotNull { e ->
                            when (e) {
                                is BrowseEntryRemote.RegularFile -> directChildName(e.fileName)
                                is BrowseEntryRemote.VideoFile -> directChildName(e.fileName)
                                else -> null
                            }
                        }
                }.getOrDefault(emptyList())
            }
        return names
            .filter { isHttpExposedMediaName(it) }
            .distinct()
            .sorted()
            .take(MAX_DIR_MEDIA_FILES)
    }

    private suspend fun findWebDavSidecarNames(
        sourceId: Long,
        source: WebDavSourceEntity,
        password: String,
        remoteRelativeFile: String,
        videoName: String,
    ): List<String> {
        val parentDir = parentRelative(remoteRelativeFile)
        val names = siblingNamesFromCache(sourceId, parentDir, smb = false)
            .ifEmpty {
                SidecarSubtitles.probeCandidateNames(videoName).filter { name ->
                    val remote = WebDavGateway.joinRelative(parentDir, name)
                    WebDavClient.fileSizeOrNull(source, password, remote, sticky = true)
                        ?.let { it > 0L } == true
                }
            }
        return SidecarSubtitles.matchSiblings(videoName, names)
    }

    private suspend fun listWebDavDirMediaNames(
        sourceId: Long,
        source: WebDavSourceEntity,
        password: String,
        parentDir: String,
    ): List<String> {
        val names = siblingNamesFromCache(sourceId, parentDir, smb = false)
            .ifEmpty {
                runCatching {
                    WebDavGateway.listDirectory(source, password, parentDir)
                        .mapNotNull { e ->
                            when (e) {
                                is BrowseEntryRemote.RegularFile -> directChildName(e.fileName)
                                is BrowseEntryRemote.VideoFile -> directChildName(e.fileName)
                                else -> null
                            }
                        }
                }.getOrDefault(emptyList())
            }
        return names
            .filter { isHttpExposedMediaName(it) }
            .distinct()
            .sorted()
            .take(MAX_DIR_MEDIA_FILES)
    }

    private suspend fun launchHttpView(
        context: Context,
        videoUri: Uri,
        displayName: String,
        mimeType: String,
        session: ExternalHttpStreamServer.Session,
        accessDir: Boolean,
    ) {
        // Sidecars for the opened video (matching stem) — always attach when present.
        val subUris = session.files.values
            .filter { SidecarSubtitles.isSidecarFor(displayName, it.displayName) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            .map { ExternalHttpStreamServer.uriFor(session.id, it.displayName) }
        val subNames = subUris.mapNotNull { it.lastPathSegment?.let { s -> Uri.decode(s) } }.toTypedArray()
        // Full folder video list when access-dir is on.
        val videoNames = if (accessDir) {
            session.files.values
                .filter { DefaultVideoPlayer.isVideoMime(it.mimeType) }
                .map { it.displayName }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        } else {
            emptyList()
        }
        val videoUris = videoNames.map { ExternalHttpStreamServer.uriFor(session.id, it) }

        // Optional: hand the whole folder as one m3u8 (VLC / mpv / etc.).
        val passPlaylist = accessDir && passFolderPlaylistEnabled() && videoNames.isNotEmpty()
        val openUri: Uri
        val openMime: String
        val openTitle: String
        if (passPlaylist) {
            val body = buildM3u8Playlist(session.id, videoNames, displayName)
            session.put(
                ExternalHttpStreamServer.FileEntry(
                    displayName = PLAYLIST_FILE_NAME,
                    mimeType = PLAYLIST_MIME,
                    sizeBytes = body.size.toLong(),
                    open = { ExternalHttpStreamServer.BytesBody(body) },
                ),
            )
            openUri = ExternalHttpStreamServer.uriFor(session.id, PLAYLIST_FILE_NAME)
            openMime = PLAYLIST_MIME
            openTitle = displayName
            logcat("OpenFileExternally") {
                "HTTP folder m3u8 $openUri videos=${videoNames.size} start=$displayName"
            }
        } else {
            openUri = videoUri
            openMime = mimeType
            openTitle = displayName
        }

        val view = DefaultVideoPlayer.videoViewIntent(openUri, openMime).apply {
            putExtra(Intent.EXTRA_TITLE, openTitle)
            val clip = ClipData.newRawUri(openTitle, openUri)
            for (i in subUris.indices) {
                clip.addItem(ClipData.Item(subNames.getOrNull(i), null, subUris[i]))
            }
            // Multi-URI clip only when not using m3u8 (playlist file is the handoff).
            if (accessDir && !passPlaylist) {
                for (u in videoUris) {
                    if (u != openUri) clip.addItem(ClipData.Item(u))
                }
            }
            if (clip.itemCount > 1) clipData = clip
            if (subUris.isNotEmpty()) {
                attachSubtitleExtras(subUris, subNames)
            }
            if (!passPlaylist && videoUris.size > 1) {
                attachPlaylistExtras(videoUris, displayName)
            }
        }
        val preferred = DefaultVideoPlayer.preferredComponentOrNull(context)
        if (preferred != null) {
            view.component = preferred
            val launched = withUIContext {
                try {
                    context.startActivity(view)
                    true
                } catch (e: ActivityNotFoundException) {
                    logcat("OpenFileExternally", e)
                    view.component = null
                    false
                }
            }
            if (launched) return
        }
        val title = context.getString(R.string.open_in_other_app)
        val chooser = Intent.createChooser(view, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withUIContext {
            try {
                context.startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                logcat("OpenFileExternally", e)
                ExternalHttpStreamServer.removeSession(session.id)
                error(context.getString(R.string.browse_open_failed))
            }
        }
    }

    /**
     * Simple progressive m3u8 (not HLS segments): absolute HTTP URLs per video.
     * Current file is listed first so players start there; remaining stay A–Z.
     */
    private fun buildM3u8Playlist(
        sessionId: String,
        videoNames: List<String>,
        startName: String,
    ): ByteArray {
        val ordered = buildList {
            val start = videoNames.firstOrNull { it.equals(startName, ignoreCase = true) }
            if (start != null) {
                add(start)
                for (n in videoNames) {
                    if (!n.equals(start, ignoreCase = true)) add(n)
                }
            } else {
                addAll(videoNames)
            }
        }
        val text = buildString(ordered.size * 96) {
            append("#EXTM3U\n")
            for (name in ordered) {
                val title = name.replace('\r', ' ').replace('\n', ' ')
                append("#EXTINF:-1,").append(title).append('\n')
                append(ExternalHttpStreamServer.uriFor(sessionId, name)).append('\n')
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    // endregion

    // region streamdoc (in-app Media3 + non-video external)

    private suspend fun registerLocalStreamdoc(
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

    private suspend fun registerSmbStreamdoc(
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
                    readahead = false,
                )
            },
        )
    }

    private suspend fun registerWebDavStreamdoc(
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

    private fun findLocalSiblingNames(videoPathStr: String): List<String> {
        if (videoPathStr.startsWith('/')) {
            val parent = File(videoPathStr).parentFile ?: return emptyList()
            return parent.list()?.toList().orEmpty()
        }
        return runCatching {
            val path = videoPathStr.toPath()
            val parent = path.parent ?: return@runCatching emptyList()
            val parentFile = File(parent.toString())
            if (parentFile.isDirectory) {
                parentFile.list()?.toList().orEmpty()
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun siblingPath(videoPathStr: String, siblingName: String): String? {
        if (videoPathStr.startsWith('/')) {
            val parent = File(videoPathStr).parentFile ?: return null
            return File(parent, siblingName).path
        }
        return runCatching {
            val path = videoPathStr.toPath()
            val parent = path.parent ?: return@runCatching null
            (parent / siblingName).toString()
        }.getOrNull()
    }

    private fun siblingNamesFromCache(sourceId: Long, parentDir: String, smb: Boolean): List<String> {
        val listing = if (smb) {
            BrowseSession.getSmbListing(sourceId, parentDir)
        } else {
            BrowseSession.getWebDavListing(sourceId, parentDir)
        } ?: return emptyList()
        return listing.mapNotNull { entry ->
            when (entry) {
                is BrowseEntryRemote.RegularFile -> directChildName(entry.fileName)
                is BrowseEntryRemote.VideoFile -> directChildName(entry.fileName)
                else -> null
            }
        }
    }

    /** Reject promoted descendants; HTTP folder access is limited to the video's directory. */
    private fun directChildName(relativeName: String): String? {
        val normalized = relativeName.replace('\\', '/')
        if ('/' in normalized) return null
        return normalized.takeIf(ExternalHttpStreamServer::isSafeFileName)
    }

    private fun parentRelative(remoteRelativeFile: String): String {
        val normalized = remoteRelativeFile.replace('\\', '/').trim('/')
        val slash = normalized.lastIndexOf('/')
        return if (slash <= 0) "" else normalized.substring(0, slash)
    }

    private suspend fun launchStreamdoc(
        context: Context,
        token: String,
        displayName: String,
        mimeType: String,
        networkStream: Boolean,
        internalPlayer: Boolean,
    ) {
        val uri = StreamDocumentProvider.uriFor(token, displayName)
        try {
            if (networkStream && !internalPlayer) requestStreamNotificationPermission(context)
            if (internalPlayer) {
                val intent = VideoPlayerActivity.intent(
                    context = context,
                    uri = uri,
                    title = displayName,
                    mimeType = mimeType,
                    streamToken = token,
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withUIContext { context.startActivity(intent) }
            } else {
                val view = DefaultVideoPlayer.videoViewIntent(uri, mimeType).apply {
                    putExtra(Intent.EXTRA_TITLE, displayName)
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
        } catch (e: Throwable) {
            StreamDocumentRegistry.remove(token)
            throw e
        }
    }

    // endregion

    private fun Intent.attachSubtitleExtras(subUris: List<Uri>, subNames: Array<String>) {
        putExtra("subs", subUris.toTypedArray())
        if (subNames.size == subUris.size) {
            putExtra("subs.name", subNames)
        }
        putExtra("subtitles_location", subUris.first().toString())
        putExtra("subtitle", subUris.first().toString())
        putExtra("subtitle_uri", subUris.first())
    }

    /**
     * Best-effort playlist extras for players that support multi-file open
     * (folder access-dir mode). Intent [data] remains the opened video.
     */
    private fun Intent.attachPlaylistExtras(videoUris: List<Uri>, currentName: String) {
        putExtra("video_list", videoUris.toTypedArray())
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(videoUris))
        // Some players want the index of the current item in the list.
        val idx = videoUris.indexOfFirst {
            Uri.decode(it.lastPathSegment.orEmpty()) == currentName
        }
        if (idx >= 0) putExtra("playlist_index", idx)
    }

    /** Cap directory publish so open-with stays snappy on huge shares. */
    private const val MAX_DIR_MEDIA_FILES = 80

    /** Virtual playlist basename served from the HTTP session (not a real on-disk file). */
    private const val PLAYLIST_FILE_NAME = "playlist.m3u8"
    private const val PLAYLIST_MIME = "application/vnd.apple.mpegurl"
}
