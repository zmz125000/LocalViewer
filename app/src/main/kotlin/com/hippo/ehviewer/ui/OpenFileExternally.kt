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
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.SidecarSubtitles
import com.hippo.ehviewer.library.VideoDirectLinkByteSource
import com.hippo.ehviewer.library.ZipMemberByteSource
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.isBrowseVideoFileName
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.openLocalArchiveByteSource
import com.hippo.ehviewer.library.withLocalZipCentralDirectory
import com.hippo.ehviewer.provider.ExternalHttpStreamServer
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.provider.requestStreamNotificationPermission
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.player.InternalVideoPlaylistRegistry
import com.hippo.ehviewer.ui.player.InternalVideoSource
import com.hippo.ehviewer.ui.player.PreparedInternalVideo
import com.hippo.ehviewer.util.PrivacyLog
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Record video + parent browse-dir history for playlist next/prev / HTTP loopback
     * playback. Parent dir bump runs inside [LocalHistory] → [com.hippo.ehviewer.EhDB.putHistoryInfo].
     */
    suspend fun recordVideoPlaybackHistory(source: InternalVideoSource) {
        when (source) {
            is InternalVideoSource.Local ->
                LocalHistory.recordLocalFile(source.path, title = source.displayName)
            is InternalVideoSource.Smb ->
                LocalHistory.recordSmbFile(
                    sourceId = source.sourceId,
                    remotePath = source.remotePath,
                    title = source.displayName,
                )
            is InternalVideoSource.WebDav ->
                LocalHistory.recordWebDavFile(
                    sourceId = source.sourceId,
                    remotePath = source.remotePath,
                    title = source.displayName,
                )
        }
    }

    /** Fire-and-forget history write from non-suspend paths (HTTP worker, etc.). */
    fun scheduleRecordVideoPlaybackHistory(source: InternalVideoSource) {
        historyScope.launch {
            runCatching { recordVideoPlaybackHistory(source) }
                .onFailure { logcat("OpenFileExternally", it) }
        }
    }

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
        playlistPaths: List<String> = emptyList(),
    ) {
        val current = InternalVideoSource.Local(pathStr)
        val candidates = playlistPaths.map { InternalVideoSource.Local(it) }
        launchInternalVideo(context, current, candidates, displayName, mimeType)
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
        playlistRemoteFiles: List<String> = emptyList(),
    ) {
        val current = InternalVideoSource.Smb(sourceId, remoteRelativeFile)
        val candidates = playlistRemoteFiles.map { InternalVideoSource.Smb(sourceId, it) }
        launchInternalVideo(context, current, candidates, displayName, mimeType)
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
        playlistRemoteFiles: List<String> = emptyList(),
    ) {
        val current = InternalVideoSource.WebDav(sourceId, remoteRelativeFile)
        val candidates = playlistRemoteFiles.map { InternalVideoSource.WebDav(sourceId, it) }
        launchInternalVideo(context, current, candidates, displayName, mimeType)
    }

    private suspend fun launchInternalVideo(
        context: Context,
        current: InternalVideoSource,
        candidates: List<InternalVideoSource>,
        displayName: String,
        mimeType: String,
    ) {
        val created = InternalVideoPlaylistRegistry.create(current, candidates)
        try {
            if (current !is InternalVideoSource.Local) {
                SmbGateway.beginVideoPlay("prepare:${current.javaClass.simpleName}")
            }
            val prepared = prepareInternalVideo(current, displayName, mimeType)
            launchStreamdoc(
                context = context,
                token = prepared.token,
                displayName = prepared.displayName,
                mimeType = prepared.mimeType,
                networkStream = prepared.network,
                internalPlayer = true,
                playlistSessionId = created.session.id,
                playlistIndex = created.initialIndex,
            )
        } catch (e: Throwable) {
            InternalVideoPlaylistRegistry.remove(created.session.id)
            throw e
        }
    }

    internal suspend fun prepareInternalVideo(
        source: InternalVideoSource,
        displayName: String = source.displayName,
        mimeType: String = source.mimeType,
    ): PreparedInternalVideo {
        val token = when (source) {
            is InternalVideoSource.Local ->
                registerLocalStreamdoc(source.path, displayName, mimeType)
            is InternalVideoSource.Smb ->
                registerSmbStreamdoc(source.sourceId, source.remotePath, displayName, mimeType)
            is InternalVideoSource.WebDav ->
                registerWebDavStreamdoc(source.sourceId, source.remotePath, displayName, mimeType)
        }
        val zipLocal = source is InternalVideoSource.Local && ZipPaths.isZipPath(source.path)
        return PreparedInternalVideo(
            token = token,
            uri = StreamDocumentProvider.uriFor(token, displayName),
            displayName = displayName,
            mimeType = mimeType,
            network = zipLocal || source !is InternalVideoSource.Local,
        )
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
    private fun passFolderPlaylistEnabled(): Boolean = accessDirEnabled() && Settings.externalVideoPassFolderPlaylist.value

    private fun isHttpExposedMediaName(name: String): Boolean = isBrowseVideoFileName(name) || SidecarSubtitles.isSubtitleFileName(name)

    private fun mediaMimeForName(name: String): String = when {
        SidecarSubtitles.isSubtitleFileName(name) -> SidecarSubtitles.mimeTypeForSubtitle(name)
        else -> mimeTypeForFileName(name)
    }

    /**
     * Obtain or reuse the HTTP session for [dirKey], then register media.
     * Reused sessions keep the same id so folder playlist / next-file opens stay on one token.
     */
    private suspend fun withDirHttpSession(
        network: Boolean,
        dirKey: String,
        configure: suspend (ExternalHttpStreamServer.Session, reused: Boolean) -> Unit,
    ): Pair<ExternalHttpStreamServer.Session, Boolean> {
        val (session, reused) = ExternalHttpStreamServer.obtainSession(network, dirKey)
        return try {
            configure(session, reused)
            session to reused
        } catch (e: Throwable) {
            if (!reused) ExternalHttpStreamServer.removeSession(session.id)
            throw e
        }
    }

    private fun localDirKey(videoPathStr: String): String {
        ZipPaths.parse(videoPathStr)?.let { (zip, member) ->
            val parent = member.substringBeforeLast('/', missingDelimiterValue = "")
            return "localzip:$zip!$parent"
        }
        val parent = if (videoPathStr.startsWith('/')) {
            File(videoPathStr).parent ?: videoPathStr
        } else {
            runCatching { videoPathStr.toPath().parent?.toString() }.getOrNull() ?: videoPathStr
        }
        return "local:${parent.trimEnd('/')}"
    }

    private fun smbDirKey(sourceId: Long, parentDir: String): String = "smb:$sourceId:${parentDir.trim().trimEnd('/')}"

    private fun webDavDirKey(sourceId: Long, parentDir: String): String = "dav:$sourceId:${parentDir.trim().trimEnd('/')}"

    /** Folder access shares one token; restricted access keeps one token per opened video. */
    private fun httpSessionKey(dirKey: String, accessDir: Boolean, displayName: String): String = if (accessDir) "$dirKey|folder" else "$dirKey|file:${displayName.length}:$displayName"

    private suspend fun openLocalVideoHttp(
        context: Context,
        pathStr: String,
        displayName: String,
        mimeType: String,
    ) {
        val accessDir = accessDirEnabled()
        val dirKey = httpSessionKey(localDirKey(pathStr), accessDir, displayName)
        val (session, reused) = withIOContext {
            withDirHttpSession(network = false, dirKey = dirKey) { session, _ ->
                // Skip live dir list only when session already has a folder playlist (≥2
                // media). A single-file session must re-list or next/prev stays broken.
                val skipLiveList = accessDir && sessionHasFolderPlaylist(session)
                session.put(localFileEntry(pathStr, displayName, mimeType))
                val extras = if (accessDir) {
                    if (skipLiveList) {
                        emptyList()
                    } else {
                        listLocalDirMediaNames(pathStr)
                            .filterNot { it.equals(displayName, ignoreCase = true) }
                    }
                } else {
                    findLocalSidecarNames(pathStr, displayName)
                }
                for (name in extras) {
                    if (session.files.containsKey(ExternalHttpStreamServer.pathKey(name))) continue
                    val subPath = siblingPath(pathStr, name) ?: continue
                    runCatching {
                        session.put(localFileEntry(subPath, name, mediaMimeForName(name)))
                    }.onFailure { logcat("OpenFileExternally", it) }
                }
            }
        }
        val videoUri = ExternalHttpStreamServer.uriFor(session.id, displayName)
        logcat("OpenFileExternally") {
            "HTTP local video session=${session.id} file=${PrivacyLog.file(displayName)} " +
                "files=${session.files.size} accessDir=$accessDir reused=$reused"
        }
        try {
            launchHttpView(context, videoUri, displayName, mimeType, session, accessDir)
        } catch (e: Throwable) {
            if (!reused) ExternalHttpStreamServer.removeSession(session.id)
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
        val (session, reused) = withIOContext {
            val source = SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
            val password = SmbPasswordStore.get(sourceId)
            // Size comes from the sticky open on first GET — do not queue behind
            // browse-pool thumbs on the data NIO group.
            val parentDir = parentRelative(remoteRelativeFile)
            val dirKey = httpSessionKey(smbDirKey(sourceId, parentDir), accessDir, displayName)
            withDirHttpSession(network = true, dirKey = dirKey) { session, wasReused ->
                // Skip live SMB list only when session already looks like a folder playlist.
                // Always merge BrowseSession / folder-index names so a 1-file failed list
                // cannot freeze next/prev after reuse (90056f / 6b204af regression).
                val skipLiveList = accessDir && sessionHasFolderPlaylist(session)
                session.put(
                    smbFileEntry(source, password, remoteRelativeFile, displayName, mimeType, sizeBytes = -1L),
                )
                val extras = if (accessDir) {
                    val cached = siblingMediaNamesFromCacheSmb(sourceId, source, parentDir)
                        .filterNot { it.equals(displayName, ignoreCase = true) }
                    val live = if (skipLiveList) {
                        emptyList()
                    } else {
                        listSmbDirMediaNames(sourceId, source, password, parentDir)
                            .filterNot { it.equals(displayName, ignoreCase = true) }
                    }
                    (cached + live).distinct()
                } else {
                    findSmbSidecarNames(sourceId, source, password, remoteRelativeFile, displayName)
                }
                for (name in extras) {
                    if (session.files.containsKey(ExternalHttpStreamServer.pathKey(name))) continue
                    val remote = SmbGateway.joinRelativePath(parentDir, name)
                    session.put(
                        smbFileEntry(
                            source,
                            password,
                            remote,
                            name,
                            mediaMimeForName(name),
                            sizeBytes = -1L,
                        ),
                    )
                }
                logcat("OpenFileExternally") {
                    "HTTP SMB session ${session.id} files=${session.files.size} " +
                        "accessDir=$accessDir reused=$wasReused skipLive=$skipLiveList " +
                        "dirKey=${PrivacyLog.dirKey(dirKey)} extras=${extras.size}"
                }
            }
        }
        val videoUri = ExternalHttpStreamServer.uriFor(session.id, displayName)
        SmbGateway.beginVideoPlay("http-open:${PrivacyLog.file(displayName)}")
        try {
            launchHttpView(context, videoUri, displayName, mimeType, session, accessDir)
        } catch (e: Throwable) {
            if (!reused) ExternalHttpStreamServer.removeSession(session.id)
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
        val (session, reused) = withIOContext {
            val source = WebDavRepository.load(sourceId) ?: throw IOException("WebDAV source missing")
            val password = WebDavPasswordStore.get(sourceId)
            val sizeBytes = WebDavClient.fileSizeOrNull(source, password, remoteRelativeFile, sticky = true)
                ?.takeIf { it > 0L }
                ?: error("empty or unreachable file")
            val parentDir = parentRelative(remoteRelativeFile)
            val dirKey = httpSessionKey(webDavDirKey(sourceId, parentDir), accessDir, displayName)
            withDirHttpSession(network = true, dirKey = dirKey) { session, wasReused ->
                val skipLiveList = accessDir && sessionHasFolderPlaylist(session)
                session.put(
                    webDavFileEntry(source, password, remoteRelativeFile, displayName, mimeType, sizeBytes),
                )
                val extras = if (accessDir) {
                    val cached = siblingMediaNamesFromCacheWebDav(sourceId, source, parentDir)
                        .filterNot { it.equals(displayName, ignoreCase = true) }
                    val live = if (skipLiveList) {
                        emptyList()
                    } else {
                        listWebDavDirMediaNames(sourceId, source, password, parentDir)
                            .filterNot { it.equals(displayName, ignoreCase = true) }
                    }
                    (cached + live).distinct()
                } else {
                    findWebDavSidecarNames(sourceId, source, password, remoteRelativeFile, displayName)
                }
                for (name in extras) {
                    if (session.files.containsKey(ExternalHttpStreamServer.pathKey(name))) continue
                    val remote = WebDavGateway.joinRelative(parentDir, name)
                    session.put(
                        webDavFileEntry(
                            source,
                            password,
                            remote,
                            name,
                            mediaMimeForName(name),
                            sizeBytes = -1L,
                        ),
                    )
                }
                logcat("OpenFileExternally") {
                    "HTTP WebDAV session ${session.id} files=${session.files.size} " +
                        "accessDir=$accessDir reused=$wasReused skipLive=$skipLiveList " +
                        "dirKey=${PrivacyLog.dirKey(dirKey)} extras=${extras.size}"
                }
            }
        }
        val videoUri = ExternalHttpStreamServer.uriFor(session.id, displayName)
        try {
            launchHttpView(context, videoUri, displayName, mimeType, session, accessDir)
        } catch (e: Throwable) {
            if (!reused) ExternalHttpStreamServer.removeSession(session.id)
            throw e
        }
    }

    private fun localFileEntry(
        pathStr: String,
        displayName: String,
        mimeType: String,
    ): ExternalHttpStreamServer.FileEntry {
        val video = DefaultVideoPlayer.isVideoMime(mimeType) || isBrowseVideoFileName(displayName)
        val onPlay = if (video) {
            {
                scheduleRecordVideoPlaybackHistory(InternalVideoSource.Local(pathStr))
            }
        } else {
            null
        }
        ZipPaths.parse(pathStr)?.let { (zipAbs, member) ->
            val zipPath = zipAbs.toPath()
            val size = openLocalArchiveByteSource(zipPath)?.use { zip ->
                ZipMemberByteSource.uncompressedSize(zip, member)
            }?.takeIf { it > 0L } ?: error("empty zip member")
            return ExternalHttpStreamServer.FileEntry(
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = size,
                cacheBody = video,
                onPlaybackStart = onPlay,
                open = {
                    val zip = openLocalArchiveByteSource(zipPath)
                        ?: error("ZIP missing: $zipAbs")
                    ExternalHttpStreamServer.ArchiveBody(
                        ZipMemberByteSource.open(zip, member, ownsZip = true)
                            ?: run {
                                runCatching { zip.close() }
                                error("Cannot stream ZIP video member $member")
                            },
                    )
                },
            )
        }
        val file = File(pathStr)
        if (pathStr.startsWith('/') && file.isFile) {
            val size = file.length().takeIf { it > 0L } ?: error("empty file")
            return ExternalHttpStreamServer.FileEntry(
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = size,
                onPlaybackStart = onPlay,
                open = { ExternalHttpStreamServer.LocalFileBody(file) },
            )
        }
        val openPfd: () -> ParcelFileDescriptor = { pathStr.toPath().openFileDescriptor("r") }
        val size = openPfd().use { it.statSize.takeIf { s -> s > 0L } ?: error("empty file") }
        return ExternalHttpStreamServer.FileEntry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = size,
            onPlaybackStart = onPlay,
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
    ): ExternalHttpStreamServer.FileEntry {
        val video = DefaultVideoPlayer.isVideoMime(mimeType) || isBrowseVideoFileName(displayName)
        return ExternalHttpStreamServer.FileEntry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            // Warm one-lane sticky across Ranges; 60s inactive / next-file evict. Pool cap 2.
            cacheBody = video,
            evictOnSmbPoolPressure = video,
            onPlaybackStart = if (video) {
                {
                    scheduleRecordVideoPlaybackHistory(
                        InternalVideoSource.Smb(source.id, remoteRelativeFile),
                    )
                }
            } else {
                null
            },
            open = {
                val openLane = {
                    SmbArchiveByteSource(
                        source = source,
                        password = password,
                        remoteRelativeFile = remoteRelativeFile,
                        preferSequential = false,
                        pipeline = false,
                        stickySession = true,
                        httpStickyPool = true,
                        knownSize = sizeBytes.takeIf { it > 0L } ?: -1L,
                        readahead = false,
                        videoPlay = video,
                    )
                }
                ExternalHttpStreamServer.ArchiveBody(
                    if (video) {
                        VideoDirectLinkByteSource.open(
                            openLane = openLane,
                            knownSize = sizeBytes,
                        )
                    } else {
                        openLane()
                    },
                )
            },
        )
    }

    private fun webDavFileEntry(
        source: WebDavSourceEntity,
        password: String,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): ExternalHttpStreamServer.FileEntry {
        val video = DefaultVideoPlayer.isVideoMime(mimeType) || isBrowseVideoFileName(displayName)
        return ExternalHttpStreamServer.FileEntry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            // Warm one-lane sticky + 60s inactive.
            cacheBody = video,
            onPlaybackStart = if (video) {
                {
                    scheduleRecordVideoPlaybackHistory(
                        InternalVideoSource.WebDav(source.id, remoteRelativeFile),
                    )
                }
            } else {
                null
            },
            open = {
                val openLane = {
                    WebDavArchiveByteSource(
                        source = source,
                        password = password,
                        remoteRelativeFile = remoteRelativeFile,
                        preferSequential = false,
                        pipeline = false,
                        stickySession = true,
                        knownSize = sizeBytes.takeIf { it > 0L } ?: -1L,
                        readahead = false,
                    )
                }
                ExternalHttpStreamServer.ArchiveBody(
                    if (video) {
                        VideoDirectLinkByteSource.open(
                            openLane = openLane,
                            knownSize = sizeBytes,
                        )
                    } else {
                        openLane()
                    },
                )
            },
        )
    }

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
        // One directory list — never probeCandidateNames × fileSize (hundreds of NOT_FOUND opens).
        val names = listSmbDirChildNames(sourceId, source, password, parentDir)
        return SidecarSubtitles.matchSiblings(videoName, names)
    }

    /**
     * Full media basenames for HTTP access-dir.
     * Prefer BrowseSession / folder-index cache first (works when browse pool was closed
     * by app background / video sticky). Union with a best-effort live share.list.
     */
    private suspend fun listSmbDirMediaNames(
        sourceId: Long,
        source: SmbSourceEntity,
        password: String,
        parentDir: String,
    ): List<String> {
        val cached = siblingMediaNamesFromCacheSmb(sourceId, source, parentDir)
        val live = runCatching {
            SmbGateway.listChildFileNames(source, password, parentDir)
        }.onFailure {
            logcat("OpenFileExternally", it)
        }.getOrDefault(emptyList())
        val names = when {
            live.isNotEmpty() && cached.isNotEmpty() -> (cached + live)
            live.isNotEmpty() -> live
            cached.isNotEmpty() -> cached
            else -> listSmbDirChildNames(sourceId, source, password, parentDir)
        }
        return names
            .filter { ExternalHttpStreamServer.isSafeFileName(it) && isHttpExposedMediaName(it) }
            .distinct()
            .sorted()
            .take(MAX_DIR_MEDIA_FILES)
    }

    private suspend fun listSmbDirChildNames(
        sourceId: Long,
        source: SmbSourceEntity,
        password: String,
        parentDir: String,
    ): List<String> {
        val cached = siblingNamesFromListing(
            BrowseSession.getSmbListing(sourceId, parentDir)
                ?: NetworkFolderIndexCache.loadSmb(sourceId, SmbGateway.sourceConfigKey(source), parentDir),
        )
        if (cached.isNotEmpty()) return cached
        val live = runCatching {
            SmbGateway.listChildFileNames(source, password, parentDir)
        }.getOrDefault(emptyList())
        if (live.isNotEmpty()) return live
        return runCatching {
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

    private suspend fun findWebDavSidecarNames(
        sourceId: Long,
        source: WebDavSourceEntity,
        password: String,
        remoteRelativeFile: String,
        videoName: String,
    ): List<String> {
        val parentDir = parentRelative(remoteRelativeFile)
        val names = listWebDavDirChildNames(sourceId, source, password, parentDir)
        return SidecarSubtitles.matchSiblings(videoName, names)
    }

    private suspend fun listWebDavDirChildNames(
        sourceId: Long,
        source: WebDavSourceEntity,
        password: String,
        parentDir: String,
    ): List<String> {
        val cached = siblingNamesFromListing(
            BrowseSession.getWebDavListing(sourceId, parentDir)
                ?: NetworkFolderIndexCache.loadWebDav(sourceId, WebDavGateway.sourceConfigKey(source), parentDir),
        )
        if (cached.isNotEmpty()) return cached
        val live = runCatching {
            WebDavGateway.listChildFileNames(source, password, parentDir)
        }.getOrDefault(emptyList())
        if (live.isNotEmpty()) return live
        return runCatching {
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

    private suspend fun listWebDavDirMediaNames(
        sourceId: Long,
        source: WebDavSourceEntity,
        password: String,
        parentDir: String,
    ): List<String> {
        val cached = siblingMediaNamesFromCacheWebDav(sourceId, source, parentDir)
        val live = runCatching {
            WebDavGateway.listChildFileNames(source, password, parentDir)
        }.onFailure {
            logcat("OpenFileExternally", it)
        }.getOrDefault(emptyList())
        val names = when {
            live.isNotEmpty() && cached.isNotEmpty() -> (cached + live)
            live.isNotEmpty() -> live
            cached.isNotEmpty() -> cached
            else -> listWebDavDirChildNames(sourceId, source, password, parentDir)
        }
        return names
            .filter { ExternalHttpStreamServer.isSafeFileName(it) && isHttpExposedMediaName(it) }
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
        val playlistName = playlistNameFor(session.id)
        val videoNames = if (accessDir) {
            session.files.values
                .filter {
                    DefaultVideoPlayer.isVideoMime(it.mimeType) &&
                        !it.displayName.equals(playlistName, ignoreCase = true)
                }
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
                    displayName = playlistName,
                    mimeType = PLAYLIST_MIME,
                    sizeBytes = body.size.toLong(),
                    open = { ExternalHttpStreamServer.BytesBody(body) },
                ),
            )
            openUri = ExternalHttpStreamServer.uriFor(session.id, playlistName)
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
            // Avoid UnsafeIntentLaunchViolation: only set full component when filters match.
            DefaultVideoPlayer.bindPreferredPlayer(context, view, preferred)
            val launched = withUIContext {
                try {
                    context.startActivity(view)
                    true
                } catch (e: ActivityNotFoundException) {
                    logcat("OpenFileExternally", e)
                    view.component = null
                    view.setPackage(null)
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
        ZipPaths.parse(pathStr)?.let { (zipAbs, member) ->
            return withIOContext {
                val zipPath = zipAbs.toPath()
                val sizeBytes = openLocalArchiveByteSource(zipPath)?.use { zip ->
                    ZipMemberByteSource.uncompressedSize(zip, member)
                }?.takeIf { it > 0L } ?: error("empty zip member")
                StreamDocumentRegistry.register(
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    openSource = {
                        val zip = openLocalArchiveByteSource(zipPath)
                            ?: error("ZIP missing: $zipAbs")
                        ZipMemberByteSource.open(zip, member, ownsZip = true)
                            ?: run {
                                runCatching { zip.close() }
                                error("Cannot stream ZIP video member $member")
                            }
                    },
                )
            }
        }
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
        val isVideo = DefaultVideoPlayer.isVideoMime(mimeType) || isBrowseVideoFileName(displayName)
        // Video size is taken from the sticky open. A browse-pool STAT waits behind thumbs
        // and the data NIO group; PDF/archives still probe so viewers get Content-Length.
        val sizeBytes = if (isVideo) {
            -1L
        } else {
            withIOContext {
                SmbGateway.fileSizeOrNull(source, password, remoteRelativeFile)
                    ?.takeIf { it > 0L }
                    ?: error("empty or unreachable file")
            }
        }
        return StreamDocumentRegistry.register(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            openSource = {
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remoteRelativeFile,
                    preferSequential = false,
                    pipeline = false,
                    stickySession = true,
                    httpStickyPool = isVideo,
                    knownSize = sizeBytes,
                    readahead = false,
                    videoPlay = isVideo,
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
        ZipPaths.parse(videoPathStr)?.let { (zipAbs, member) ->
            val prefix = member.substringBeforeLast('/', missingDelimiterValue = "")
            return withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                cd.entries.mapNotNull { entry ->
                    if (entry.isDirectory) return@mapNotNull null
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    val parent = name.substringBeforeLast('/', missingDelimiterValue = "")
                    if (parent != prefix) return@mapNotNull null
                    val leaf = name.substringAfterLast('/')
                    leaf.takeIf { isHttpExposedMediaName(it) }
                }
            }.orEmpty()
        }
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
        ZipPaths.parse(videoPathStr)?.let { (zipAbs, member) ->
            val parent = member.substringBeforeLast('/', missingDelimiterValue = "")
            val rel = if (parent.isEmpty()) siblingName else "$parent/$siblingName"
            return ZipPaths.encode(zipAbs, rel)
        }
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

    /** True when the HTTP session already has a usable folder playlist (not a lone current file). */
    private fun sessionHasFolderPlaylist(session: ExternalHttpStreamServer.Session): Boolean {
        var media = 0
        for (entry in session.files.values) {
            if (!isHttpExposedMediaName(entry.displayName)) continue
            media++
            if (media >= 2) return true
        }
        return false
    }

    private suspend fun siblingMediaNamesFromCacheSmb(
        sourceId: Long,
        source: SmbSourceEntity,
        parentDir: String,
    ): List<String> = siblingNamesFromListing(
        BrowseSession.getSmbListing(sourceId, parentDir)
            ?: NetworkFolderIndexCache.loadSmb(sourceId, SmbGateway.sourceConfigKey(source), parentDir),
    ).filter { isHttpExposedMediaName(it) }

    private suspend fun siblingMediaNamesFromCacheWebDav(
        sourceId: Long,
        source: WebDavSourceEntity,
        parentDir: String,
    ): List<String> = siblingNamesFromListing(
        BrowseSession.getWebDavListing(sourceId, parentDir)
            ?: NetworkFolderIndexCache.loadWebDav(sourceId, WebDavGateway.sourceConfigKey(source), parentDir),
    ).filter { isHttpExposedMediaName(it) }

    private fun siblingNamesFromListing(listing: List<BrowseEntryRemote>?): List<String> {
        if (listing.isNullOrEmpty()) return emptyList()
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
        playlistSessionId: String? = null,
        playlistIndex: Int = 0,
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
                    playlistSessionId = playlistSessionId,
                    playlistIndex = playlistIndex,
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                withUIContext { context.startActivity(intent) }
            } else {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    /**
     * Cap directory publish for HTTP access-dir / playlist extras.
     * High enough for large video folders (300+) without unbounded multi-GB intent clips.
     */
    private const val MAX_DIR_MEDIA_FILES = 2000

    /** Virtual playlist basename served from the HTTP session (not a real on-disk file). */
    private fun playlistNameFor(sessionId: String): String = ".localviewer-$sessionId.m3u8"

    private const val PLAYLIST_MIME = "video/x-mpegurl"
}
