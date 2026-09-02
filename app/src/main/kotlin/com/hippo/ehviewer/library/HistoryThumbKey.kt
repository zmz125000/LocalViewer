package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.webdav.WebDavCache
import okio.Path
import okio.Path.Companion.toPath

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
 * - Videos: `vid-local:{path}` / `vid-smb:…` / `vid-dav:…` → [VideoThumbnail] cache hit
 *   only. Disk identity is path/source only (not listing size); matches browse frames.
 *
 * Dir-only browse history (`*_browse` tokens) uses the **folder** thumb key
 * (local path / `smb-thumb:` / `dav-thumb:`), same as folder favourites — not archive keys.
 */
object HistoryThumbKey {
    private const val SMB_PREFIX = "smb-thumb:"
    private const val DAV_PREFIX = "dav-thumb:"
    private const val SMB_ARCH_PREFIX = "smb-arch:"
    private const val DAV_ARCH_PREFIX = "dav-arch:"
    private const val SMB_ZIP_PREFIX = "smb-zip:"
    private const val DAV_ZIP_PREFIX = "dav-zip:"
    private const val VID_LOCAL_PREFIX = "vid-local:"
    private const val VID_SMB_PREFIX = "vid-smb:"
    private const val VID_DAV_PREFIX = "vid-dav:"

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

    /** Extracted ZIP member JPEG in [ZipMemberCover] (`smb:{id}:{zipRel}` dest key). */
    fun smbZip(sourceId: Long, zipRel: String, memberRel: String): String {
        val zip = zipRel.replace('\\', '/').trimStart('/')
        val member = memberRel.replace('\\', '/').trimStart('/')
        return "$SMB_ZIP_PREFIX$sourceId:$zip!$member"
    }

    fun webdavZip(sourceId: Long, zipRel: String, memberRel: String): String {
        val zip = zipRel.replace('\\', '/').trimStart('/')
        val member = memberRel.replace('\\', '/').trimStart('/')
        return "$DAV_ZIP_PREFIX$sourceId:$zip!$member"
    }

    /** Local video frame in [VideoThumbnail] cache (path may be absolute or content URI). */
    fun videoLocal(path: String): String = "$VID_LOCAL_PREFIX$path"

    fun videoSmb(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$VID_SMB_PREFIX$sourceId:$remote"
    }

    fun videoWebdav(sourceId: Long, remoteRelativeFile: String): String {
        val remote = remoteRelativeFile.replace('\\', '/').trimStart('/')
        return "$VID_DAV_PREFIX$sourceId:$remote"
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
        key.startsWith(DAV_ARCH_PREFIX) ||
        key.startsWith(SMB_ZIP_PREFIX) ||
        key.startsWith(DAV_ZIP_PREFIX) ||
        key.startsWith(VID_LOCAL_PREFIX) ||
        key.startsWith(VID_SMB_PREFIX) ||
        key.startsWith(VID_DAV_PREFIX) ||
        ZipPaths.isZipPath(key)

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
            key.startsWith(SMB_ZIP_PREFIX) -> {
                val parsed = parseZipMemberKey(key, SMB_ZIP_PREFIX) ?: return null
                val dest = ZipMemberCover.destFile("smb:${parsed.first}:${parsed.second}", parsed.third)
                return dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath
            }
            key.startsWith(DAV_ZIP_PREFIX) -> {
                val parsed = parseZipMemberKey(key, DAV_ZIP_PREFIX) ?: return null
                val dest = ZipMemberCover.destFile("webdav:${parsed.first}:${parsed.second}", parsed.third)
                return dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath
            }
            key.startsWith(VID_LOCAL_PREFIX) -> {
                val path = key.removePrefix(VID_LOCAL_PREFIX)
                if (path.isEmpty()) return null
                return VideoThumbnail.cachedJpegIfPresent(VideoThumbnailSource.Local(path))?.absolutePath
            }
            key.startsWith(VID_SMB_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, VID_SMB_PREFIX) ?: return null
                return VideoThumbnail.cachedJpegIfPresent(
                    VideoThumbnailSource.Smb(sourceId, remote),
                )?.absolutePath
            }
            key.startsWith(VID_DAV_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, VID_DAV_PREFIX) ?: return null
                return VideoThumbnail.cachedJpegIfPresent(
                    VideoThumbnailSource.WebDav(sourceId, remote),
                )?.absolutePath
            }
            else -> {
                ZipPaths.parse(key)?.let { (zip, member) ->
                    val dest = ZipMemberCover.destFile(zip, member)
                    return dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath
                }
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
            key.startsWith(SMB_ZIP_PREFIX) -> {
                val parsed = parseZipMemberKey(key, SMB_ZIP_PREFIX) ?: return null
                return ZipMemberCover.destFile("smb:${parsed.first}:${parsed.second}", parsed.third)
                    .absolutePath.toPath()
            }
            key.startsWith(DAV_ZIP_PREFIX) -> {
                val parsed = parseZipMemberKey(key, DAV_ZIP_PREFIX) ?: return null
                return ZipMemberCover.destFile("webdav:${parsed.first}:${parsed.second}", parsed.third)
                    .absolutePath.toPath()
            }
            key.startsWith(VID_LOCAL_PREFIX) -> {
                val path = key.removePrefix(VID_LOCAL_PREFIX)
                if (path.isEmpty()) return null
                return VideoThumbnail.cachedJpegIfPresent(VideoThumbnailSource.Local(path))
                    ?.absolutePath?.toPath()
            }
            key.startsWith(VID_SMB_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, VID_SMB_PREFIX) ?: return null
                return VideoThumbnail.cachedJpegIfPresent(VideoThumbnailSource.Smb(sourceId, remote))
                    ?.absolutePath?.toPath()
            }
            key.startsWith(VID_DAV_PREFIX) -> {
                val (sourceId, remote) = parseSourceRemote(key, VID_DAV_PREFIX) ?: return null
                return VideoThumbnail.cachedJpegIfPresent(VideoThumbnailSource.WebDav(sourceId, remote))
                    ?.absolutePath?.toPath()
            }
            else -> {
                ZipPaths.parse(key)?.let { (zip, member) ->
                    return ZipMemberCover.destFile(zip, member).absolutePath.toPath()
                }
                return null
            }
        }
    }

    private fun parseZipMemberKey(key: String, prefix: String): Triple<Long, String, String>? {
        val rest = key.removePrefix(prefix)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val sourceId = rest.substring(0, sep).toLongOrNull() ?: return null
        val path = rest.substring(sep + 1)
        val bang = path.indexOf('!')
        if (bang <= 0 || bang >= path.length - 1) return null
        val zip = path.substring(0, bang).replace('\\', '/').trimStart('/')
        val member = path.substring(bang + 1).replace('\\', '/').trimStart('/')
        if (zip.isEmpty() || member.isEmpty()) return null
        return Triple(sourceId, zip, member)
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
