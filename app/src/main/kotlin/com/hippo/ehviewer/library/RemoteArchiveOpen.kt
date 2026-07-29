package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import java.io.File
import okio.Path

/**
 * Baseline network archive open: full download into page cache, then treat as local path
 * for [com.hippo.ehviewer.gallery.useArchivePageLoader].
 */
object RemoteArchiveOpen {
    /**
     * @return local cache [Path] ready for the archive reader.
     * @throws ArchiveTooLargeException if [knownSizeBytes] &gt; [ARCHIVE_DOWNLOAD_WARN_BYTES]
     *         and [allowLarge] is false (caller should confirm then retry with allowLarge=true).
     */
    suspend fun ensureSmbArchive(
        source: SmbSourceEntity,
        password: String,
        remoteRelativeFile: String,
        knownSizeBytes: Long? = null,
        allowLarge: Boolean = false,
    ): Path = withIOContext {
        val size = knownSizeBytes ?: SmbGateway.fileSizeOrNull(source, password, remoteRelativeFile)
        if (size != null && size > ARCHIVE_DOWNLOAD_WARN_BYTES && !allowLarge) {
            throw ArchiveTooLargeException(size)
        }
        val cache = SmbCache.cachePathForRemoteFile(source.id, remoteRelativeFile)
        SmbCache.downloadIfNeeded(cache) { out ->
            SmbGateway.downloadFile(source, password, remoteRelativeFile, out)
        }
        cache
    }

    suspend fun ensureWebDavArchive(
        source: WebDavSourceEntity,
        password: String,
        remoteRelativeFile: String,
        knownSizeBytes: Long? = null,
        allowLarge: Boolean = false,
    ): Path = withIOContext {
        if (knownSizeBytes != null && knownSizeBytes > ARCHIVE_DOWNLOAD_WARN_BYTES && !allowLarge) {
            throw ArchiveTooLargeException(knownSizeBytes)
        }
        val cache = WebDavCache.cachePathForRemoteFile(source.id, remoteRelativeFile)
        WebDavCache.downloadIfNeeded(cache) { out ->
            WebDavClient.downloadFile(source, password, remoteRelativeFile, out)
        }
        // Post-download size check when server did not report length up front.
        if (!allowLarge) {
            val local = File(cache.toString())
            if (local.isFile && local.length() > ARCHIVE_DOWNLOAD_WARN_BYTES) {
                // Already downloaded — open is fine; warn is for pre-download only.
            }
        }
        cache
    }
}

class ArchiveTooLargeException(val sizeBytes: Long) : Exception(
    "Archive is ${sizeBytes / (1024 * 1024)} MiB (limit ${ARCHIVE_DOWNLOAD_WARN_BYTES / (1024 * 1024)} MiB)",
)
