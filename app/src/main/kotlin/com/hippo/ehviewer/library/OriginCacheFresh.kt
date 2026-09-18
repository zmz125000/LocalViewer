package com.hippo.ehviewer.library

/** Size and last-write from one remote QUERY_INFO / HEAD / PROPFIND. */
data class RemoteFileStat(
    val size: Long? = null,
    val mtimeMs: Long? = null,
)

/**
 * Open / Share / Save-to origin-cache freshness.
 *
 * Regular files: stale when the remote size is known and differs from the
 * cache length, or the remote last-write is known and later than the cache
 * mtime. Unknown remote fields keep the cache.
 *
 * Zip-as-dir members must not compare the zip file's size to the extracted
 * member. Pass [compareSize] false and use the zip file's mtime (never a
 * central-directory walk of that member).
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

    /**
     * True when [remoteSize] is a known length that does not match the cache.
     * Null remote size keeps the cache.
     */
    fun sizeMismatch(remoteSize: Long?, cacheSize: Long): Boolean {
        val remote = remoteSize ?: return false
        return remote >= 0L && remote != cacheSize
    }

    fun isStale(
        remoteSize: Long?,
        remoteMtimeMs: Long?,
        cacheSize: Long,
        cacheMtimeMs: Long,
        compareSize: Boolean = true,
    ): Boolean {
        if (compareSize && sizeMismatch(remoteSize, cacheSize)) return true
        return remoteNewerThanCache(remoteMtimeMs, cacheMtimeMs)
    }

    fun isStale(
        stat: RemoteFileStat?,
        cacheSize: Long,
        cacheMtimeMs: Long,
        compareSize: Boolean = true,
    ): Boolean = isStale(
        remoteSize = stat?.size,
        remoteMtimeMs = stat?.mtimeMs,
        cacheSize = cacheSize,
        cacheMtimeMs = cacheMtimeMs,
        compareSize = compareSize,
    )
}
