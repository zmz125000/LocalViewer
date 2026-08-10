package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.webdav.WebDavCache
import okio.Path

/**
 * Stable [GalleryInfo.thumbKey] encodings for history / favourite rows so UI paints the
 * same JPEG as browse covers when the disk cache already has it (**no network**).
 *
 * - Local folder gallery: absolute cover path (filesystem / content URI).
 * - SMB / WebDAV folder gallery: `smb-thumb:{sourceId}:{remoteRelativeFile}` /
 *   `dav-thumb:…` → [SmbCache] / [WebDavCache] thumb JPEG.
 * - SMB / WebDAV network archive: `smb-arch:{sourceId}:{remote}` /
 *   `dav-arch:…` → [ArchiveCoverCache] first-page JPEG (`archive_thumb/`, same key as
 *   browse `smb:` / `webdav:` stream covers).
 *
 * Dir-only browse history (`*_browse` tokens) does not store thumbs.
 */
object HistoryThumbKey {
    private const val SMB_PREFIX = "smb-thumb:"
    private const val DAV_PREFIX = "dav-thumb:"
    private const val SMB_ARCH_PREFIX = "smb-arch:"
    private const val DAV_ARCH_PREFIX = "dav-arch:"

    fun smb(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$SMB_PREFIX$sourceId:$remote"
    }

    fun webdav(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$DAV_PREFIX$sourceId:$remote"
    }

    /** Network archive cover identity (ZIP/RAR/… first page in [ArchiveCoverCache]). */
    fun smbArchive(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$SMB_ARCH_PREFIX$sourceId:$remote"
    }

    fun webdavArchive(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$DAV_ARCH_PREFIX$sourceId:$remote"
    }

    /** Browse/reader [ArchiveCoverCache] key for a network archive logical thumb key. */
    fun archiveCacheKey(key: String): String? = when {
        key.startsWith(SMB_ARCH_PREFIX) -> {
            val (sourceId, remote) = parseSourceRemote(key, SMB_ARCH_PREFIX) ?: return null
            "smb:$sourceId:$remote"
        }
        key.startsWith(DAV_ARCH_PREFIX) -> {
            val (sourceId, remote) = parseSourceRemote(key, DAV_ARCH_PREFIX) ?: return null
            "webdav:$sourceId:$remote"
        }
        else -> null
    }

    fun isLogicalKey(key: String): Boolean = key.startsWith(SMB_PREFIX) ||
        key.startsWith(DAV_PREFIX) ||
        key.startsWith(SMB_ARCH_PREFIX) ||
        key.startsWith(DAV_ARCH_PREFIX)

    /**
     * Path Coil can open, or null if missing / not cached.
     * Call from IO (disk probe). Touches mtime on remote folder-thumb hits.
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
            key.startsWith(SMB_ARCH_PREFIX) || key.startsWith(DAV_ARCH_PREFIX) -> {
                val cacheKey = archiveCacheKey(key) ?: return null
                val dest = ArchiveCoverCache.resolveCoverDest(cacheKey)
                if (!ArchiveCoverCache.isCachedOnDisk(dest)) return null
                return dest.toString()
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
            key.startsWith(SMB_ARCH_PREFIX) || key.startsWith(DAV_ARCH_PREFIX) -> {
                val cacheKey = archiveCacheKey(key) ?: return null
                return ArchiveCoverCache.resolveCoverDest(cacheKey)
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
