package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import okio.Path

/**
 * Result of ensuring a remote archive is on disk for the local archive reader.
 * [didDownload] is false when the page cache already held the file (no network body).
 */
data class RemoteArchiveLocal(
    val path: Path,
    val didDownload: Boolean,
)

/**
 * Baseline network archive open: full download into page cache, then treat as local path
 * for [com.hippo.ehviewer.gallery.useArchivePageLoader].
 */
object RemoteArchiveOpen {
    /** Stable cache key segment: trim, unify slashes, drop empty parts. */
    fun normalizeRemoteRelative(path: String): String =
        path.replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

    fun smbCachePath(sourceId: Long, remoteRelativeFile: String): Path =
        SmbCache.cachePathForRemoteFile(sourceId, normalizeRemoteRelative(remoteRelativeFile))

    fun webDavCachePath(sourceId: Long, remoteRelativeFile: String): Path =
        WebDavCache.cachePathForRemoteFile(sourceId, normalizeRemoteRelative(remoteRelativeFile))

    /**
     * @return local cache path + whether a network download ran.
     * @throws ArchiveTooLargeException if remote size &gt; [ARCHIVE_DOWNLOAD_WARN_BYTES]
     *         and [allowLarge] is false **and** the file is not already cached.
     */
    suspend fun ensureSmbArchive(
        source: SmbSourceEntity,
        password: String,
        remoteRelativeFile: String,
        knownSizeBytes: Long? = null,
        allowLarge: Boolean = false,
        /** Called only when a real network download is about to start. */
        onWillDownload: (suspend () -> Unit)? = null,
    ): RemoteArchiveLocal = withIOContext {
        val remote = normalizeRemoteRelative(remoteRelativeFile)
        val cache = SmbCache.cachePathForRemoteFile(source.id, remote)
        // Cache hit: never re-probe size or re-download.
        if (SmbCache.isCachedOnDisk(cache)) {
            SmbCache.touch(cache)
            return@withIOContext RemoteArchiveLocal(cache, didDownload = false)
        }
        val size = knownSizeBytes ?: SmbGateway.fileSizeOrNull(source, password, remote)
        if (size != null && size > ARCHIVE_DOWNLOAD_WARN_BYTES && !allowLarge) {
            throw ArchiveTooLargeException(size)
        }
        var downloaded = false
        onWillDownload?.invoke()
        SmbCache.downloadIfNeeded(cache, originalFileName = null) { out ->
            downloaded = true
            SmbGateway.downloadFile(source, password, remote, out)
        }
        RemoteArchiveLocal(cache, didDownload = downloaded)
    }

    suspend fun ensureWebDavArchive(
        source: WebDavSourceEntity,
        password: String,
        remoteRelativeFile: String,
        knownSizeBytes: Long? = null,
        allowLarge: Boolean = false,
        onWillDownload: (suspend () -> Unit)? = null,
    ): RemoteArchiveLocal = withIOContext {
        val remote = normalizeRemoteRelative(remoteRelativeFile)
        val cache = WebDavCache.cachePathForRemoteFile(source.id, remote)
        if (WebDavCache.isCachedOnDisk(cache)) {
            WebDavCache.touch(cache)
            return@withIOContext RemoteArchiveLocal(cache, didDownload = false)
        }
        if (knownSizeBytes != null && knownSizeBytes > ARCHIVE_DOWNLOAD_WARN_BYTES && !allowLarge) {
            throw ArchiveTooLargeException(knownSizeBytes)
        }
        var downloaded = false
        onWillDownload?.invoke()
        WebDavCache.downloadIfNeeded(cache, originalFileName = null) { out ->
            downloaded = true
            WebDavClient.downloadFile(source, password, remote, out)
        }
        RemoteArchiveLocal(cache, didDownload = downloaded)
    }
}

class ArchiveTooLargeException(val sizeBytes: Long) : Exception(
    "Archive is ${sizeBytes / (1024 * 1024)} MiB (limit ${ARCHIVE_DOWNLOAD_WARN_BYTES / (1024 * 1024)} MiB)",
)
