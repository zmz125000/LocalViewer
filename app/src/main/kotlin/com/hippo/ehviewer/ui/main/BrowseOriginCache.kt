package com.hippo.ehviewer.ui.main

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
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
 * members). Hits skip the download. Writes land in [SmbCache] / [WebDavCache] /
 * [ZipMemberCover] so [com.hippo.ehviewer.library.OriginDiskCache] LRU trims them.
 */
object BrowseOriginCache {
    fun smbHit(sourceId: Long, relativeFile: String): Path? {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return zipMemberHit("smb:$sourceId:$zipRel", member)
        }
        val path = SmbCache.cachePathForRemoteFile(sourceId, relativeFile)
        val resolved = SmbCache.resolveReaderPath(path)
        return resolved.takeIf { SmbCache.isPageCachedOnDisk(it) }
    }

    fun webDavHit(sourceId: Long, relativeFile: String): Path? {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            return zipMemberHit("webdav:$sourceId:$zipRel", member)
        }
        val path = WebDavCache.cachePathForRemoteFile(sourceId, relativeFile)
        val resolved = WebDavCache.resolveReaderPath(path)
        return resolved.takeIf { WebDavCache.isPageCachedOnDisk(it) }
    }

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

    private fun zipMemberHit(zipKey: String, member: String): Path? {
        val dest = ZipMemberCover.destFile(zipKey, member)
        if (dest.isFile && dest.length() > 0L) {
            dest.setLastModified(System.currentTimeMillis())
            return dest.absolutePath.toPath()
        }
        return null
    }
}
