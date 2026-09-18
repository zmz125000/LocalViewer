package com.hippo.ehviewer.library

/**
 * Open / Share origin-cache freshness: reuse the on-disk file unless the remote
 * last-write is known and later than the cache file mtime.
 */
object OriginCacheFresh {
    /**
     * True when [remoteMtimeMs] is a known remote last-write later than the
     * cached file. Null or non-positive remote mtime keeps the cache.
     */
    fun remoteNewerThanCache(remoteMtimeMs: Long?, cacheMtimeMs: Long): Boolean {
        val remote = remoteMtimeMs ?: return false
        return remote > 0L && remote > cacheMtimeMs
    }
}
