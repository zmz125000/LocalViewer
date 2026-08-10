package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.webdav.WebDavCache
import okio.Path

/**
 * Stable [GalleryInfo.thumbKey] encodings for **folder-gallery** history rows so History
 * paints the same JPEG as browse covers / [SmbCache] / [WebDavCache].
 *
 * - Local folder gallery: absolute cover path (filesystem / content URI).
 * - SMB / WebDAV folder gallery: `smb-thumb:{sourceId}:{remoteRelativeFile}` /
 *   `dav-thumb:…`. [resolveReadablePath] maps to the thumb cache file **only on disk hit**
 *   (no network from History).
 *
 * Dir-only browse history (`*_browse` tokens) does not store thumbs.
 */
object HistoryThumbKey {
    private const val SMB_PREFIX = "smb-thumb:"
    private const val DAV_PREFIX = "dav-thumb:"

    fun smb(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$SMB_PREFIX$sourceId:$remote"
    }

    fun webdav(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$DAV_PREFIX$sourceId:$remote"
    }

    fun isLogicalKey(key: String): Boolean = key.startsWith(SMB_PREFIX) || key.startsWith(DAV_PREFIX)

    /**
     * Path Coil can open, or null if missing / not cached.
     * Call from IO (disk probe). Touches mtime on remote thumb hits.
     */
    fun resolveReadablePath(key: String?): String? {
        if (key.isNullOrBlank()) return null
        when {
            key.startsWith(SMB_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, SMB_PREFIX) ?: return null
                val cache = SmbCache.thumbCachePath(sourceId, remote)
                if (!SmbCache.isCachedOnDisk(cache)) return null
                SmbCache.touch(cache)
                return cache.toString()
            }
            key.startsWith(DAV_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, DAV_PREFIX) ?: return null
                val cache = WebDavCache.thumbCachePath(sourceId, remote)
                if (!WebDavCache.isCachedOnDisk(cache)) return null
                WebDavCache.touch(cache)
                return cache.toString()
            }
            else -> {
                // Local path / content URI / archive_thumb absolute path.
                return if (ArchiveCoverCache.isCoverPathReadable(key)) key else null
            }
        }
    }

    /** Disk path for a logical SMB/WebDAV key (may not exist). Null for local keys. */
    fun cachePathOrNull(key: String?): Path? {
        if (key.isNullOrBlank()) return null
        when {
            key.startsWith(SMB_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, SMB_PREFIX) ?: return null
                return SmbCache.thumbCachePath(sourceId, remote)
            }
            key.startsWith(DAV_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, DAV_PREFIX) ?: return null
                return WebDavCache.thumbCachePath(sourceId, remote)
            }
            else -> return null
        }
    }

    private fun parseSourceRemote(key: String, prefix: String): Pair<Long, String>? {
        val rest = key.removePrefix(prefix)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val sourceId = rest.substring(0, sep).toLongOrNull() ?: return null
        val remote = rest.substring(sep + 1).trimStart('/')
        if (remote.isEmpty()) return null
        return sourceId to remote
    }
}
