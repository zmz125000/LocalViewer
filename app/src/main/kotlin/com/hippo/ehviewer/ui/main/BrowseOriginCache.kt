package com.hippo.ehviewer.ui.main

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.OriginCacheFresh
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import java.io.File
import okio.Path
import okio.Path.Companion.toPath

/**
 * Origin-disk cache used by Share and Open for network files (including zip-as-dir
 * members). Same cache file and [ensureSmb] / [ensureWebDav] download for both.
 * Hits skip the download when the remote last-write is not newer than the cache
 * mtime. Writes land in [SmbCache] / [WebDavCache] / [ZipMemberCover] so
 * [com.hippo.ehviewer.library.OriginDiskCache] LRU trims them.
 */
object BrowseOriginCache {
    fun smbHit(sourceId: Long, relativeFile: String): Path? {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return zipMemberHit("smb:$sourceId:$zipRel", member)
        }
        val path = SmbCache.cachePathForRemoteFile(sourceId, relativeFile)
        val resolved = SmbCache.resolveReaderPath(path)
        return resolved.takeIf { SmbCache.isCachedOnDisk(it) }
    }

    fun webDavHit(sourceId: Long, relativeFile: String): Path? {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return zipMemberHit("webdav:$sourceId:$zipRel", member)
        }
        val path = WebDavCache.cachePathForRemoteFile(sourceId, relativeFile)
        val resolved = WebDavCache.resolveReaderPath(path)
        return resolved.takeIf { WebDavCache.isCachedOnDisk(it) }
    }

    /**
     * Existing origin file if present and not older than the remote last-write.
     * Stale files are deleted so [ensureSmb] re-downloads. LRU touch only on keep.
     */
    suspend fun smbFreshHit(
        source: SmbSourceEntity,
        password: String,
        relativeFile: String,
    ): Path? = takeFreshHit(
        cached = smbHit(source.id, relativeFile),
        remoteMtime = { SmbGateway.fileMtimeOrNull(source, password, relativeFile) },
        evict = { evictCached(source.id, relativeFile, smb = true) },
    )

    /** @see smbFreshHit */
    suspend fun webDavFreshHit(
        source: WebDavSourceEntity,
        password: String,
        relativeFile: String,
    ): Path? = takeFreshHit(
        cached = webDavHit(source.id, relativeFile),
        remoteMtime = { WebDavClient.fileMtimeOrNull(source, password, relativeFile) },
        evict = { evictCached(source.id, relativeFile, smb = false) },
    )

    suspend fun smbSize(
        source: SmbSourceEntity,
        password: String,
        relativeFile: String,
    ): Long? {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return ZipMemberCover.memberUncompressedSize(member) {
                SmbArchiveByteSource(source, password, zipRel, pipeline = false, yieldable = true)
            }
        }
        return SmbGateway.fileSizeOrNull(source, password, relativeFile)
    }

    suspend fun webDavSize(
        source: WebDavSourceEntity,
        password: String,
        relativeFile: String,
    ): Long? {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return ZipMemberCover.memberUncompressedSize(member) {
                WebDavArchiveByteSource(source, password, zipRel, pipeline = false)
            }
        }
        return WebDavClient.fileSizeOrNull(source, password, relativeFile)
    }

    suspend fun ensureSmb(
        source: SmbSourceEntity,
        password: String,
        relativeFile: String,
        displayName: String,
        counter: ByteCounter,
        maxZipBytes: Long = Long.MAX_VALUE,
    ): Path {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return ZipMemberCover.materialize(
                zipKey = "smb:${source.id}:$zipRel",
                memberRel = member,
                maxBytes = maxZipBytes,
                onBytes = { n -> counter.add(n) },
            ) {
                SmbArchiveByteSource(source, password, zipRel, pipeline = false, yieldable = true)
            }
        }
        val path = SmbCache.cachePathForRemoteFile(source.id, relativeFile)
        SmbCache.downloadIfNeeded(path, originalFileName = displayName) { out ->
            SmbGateway.downloadFile(source, password, relativeFile, CountingOutputStream(out, counter))
        }
        return SmbCache.resolveReaderPath(path)
    }

    suspend fun ensureWebDav(
        source: WebDavSourceEntity,
        password: String,
        relativeFile: String,
        displayName: String,
        counter: ByteCounter,
        maxZipBytes: Long = Long.MAX_VALUE,
    ): Path {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return ZipMemberCover.materialize(
                zipKey = "webdav:${source.id}:$zipRel",
                memberRel = member,
                maxBytes = maxZipBytes,
                onBytes = { n -> counter.add(n) },
            ) {
                WebDavArchiveByteSource(source, password, zipRel, pipeline = false)
            }
        }
        val path = WebDavCache.cachePathForRemoteFile(source.id, relativeFile)
        WebDavCache.downloadIfNeeded(path, originalFileName = displayName) { out ->
            WebDavClient.downloadFile(source, password, relativeFile, CountingOutputStream(out, counter))
        }
        return WebDavCache.resolveReaderPath(path)
    }

    private suspend fun takeFreshHit(
        cached: Path?,
        remoteMtime: suspend () -> Long?,
        evict: () -> Unit,
    ): Path? {
        if (cached == null) return null
        val cacheMs = File(cached.toString()).lastModified()
        if (OriginCacheFresh.remoteNewerThanCache(remoteMtime(), cacheMs)) {
            evict()
            return null
        }
        File(cached.toString()).takeIf { it.isFile }?.setLastModified(System.currentTimeMillis())
        return cached
    }

    private fun evictCached(sourceId: Long, relativeFile: String, smb: Boolean) {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            val key = if (smb) "smb:$sourceId:$zipRel" else "webdav:$sourceId:$zipRel"
            ZipMemberCover.destFile(key, member).delete()
            return
        }
        val primary = if (smb) {
            SmbCache.cachePathForRemoteFile(sourceId, relativeFile)
        } else {
            WebDavCache.cachePathForRemoteFile(sourceId, relativeFile)
        }
        val uhdr = HdrConvertCache.uhdrSiblingOf(primary)
        dropCached(primary, smb)
        if (uhdr.toString() != primary.toString()) dropCached(uhdr, smb)
    }

    private fun dropCached(path: Path, smb: Boolean) {
        File(path.toString()).delete()
        if (smb) SmbCache.markAbsent(path) else WebDavCache.markAbsent(path)
    }

    private fun zipMemberHit(zipKey: String, member: String): Path? {
        val dest = ZipMemberCover.destFile(zipKey, member)
        return dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath?.toPath()
    }
}
