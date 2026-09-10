package com.hippo.ehviewer.webdav

import android.os.Looper
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.OriginDiskCache
import com.hippo.ehviewer.util.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Disk cache for WebDAV (mirrors [com.hippo.ehviewer.smb.SmbCache] split).
 * - Pages: `webdav_cache/` full files — unified origin budget ([OriginDiskCache])
 * - Thumbs: `webdav_thumb_cache/` small WebP (leftover JPEG until LRU) — shared thumb budget
 */
object WebDavCache {
    enum class Kind { Page, Thumb }

    const val THUMB_DISK_EDGE = OriginDiskCache.THUMB_EDGE
    private const val THUMB_WEBP_QUALITY = OriginDiskCache.THUMB_QUALITY
    private const val THUMB_FORMAT_VERSION = 2
    private val thumbFetchSlots = Semaphore(3)

    /** Share folder/gallery thumbnail network capacity with video prefix fetches. */
    suspend fun <T> withBrowseThumbFetchSlot(block: suspend () -> T): T = thumbFetchSlots.withPermit { block() }

    /**
     * Pure path from dataDir string — no [Context.getCacheDir]/[mkdirs] on path resolve
     * (browse thumbs call this on main during composition).
     */
    private val pageRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/webdav_cache").toOkioPath()
    }
    private val thumbRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/webdav_thumb_cache").toOkioPath()
    }

    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    /** Paths known to exist after a successful write or off-main probe — avoids main-thread File I/O. */
    private val knownPresent = ConcurrentHashMap.newKeySet<String>()

    private fun ensureRootDirs() {
        File(pageRoot.toString()).mkdirs()
        File(thumbRoot.toString()).mkdirs()
    }

    fun cachePath(sourceId: Long, remoteRelativePath: String, fileName: String): Path = cachePath(sourceId, remoteRelativePath, fileName, Kind.Page)

    fun cachePath(
        sourceId: Long,
        remoteRelativePath: String,
        fileName: String,
        kind: Kind,
    ): Path {
        val dir = remoteRelativePath.replace('\\', '/').trim('/')
        val name = fileName.replace('\\', '/').substringAfterLast('/')
        val remote = if (dir.isEmpty()) name else "$dir/$name"
        return if (kind == Kind.Thumb) {
            thumbCachePath(sourceId, remote)
        } else {
            val key = "dav:$sourceId:$remote"
            val hash = sha256Hex(key)
            val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: "bin"
            pageRoot / HdrConvertCache.networkStorageName(hash, ext)
        }
    }

    fun cachePathForRemoteFile(sourceId: Long, remoteRelativeFile: String, kind: Kind = Kind.Page): Path {
        val normalized = remoteRelativeFile
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
        if (kind == Kind.Thumb) return thumbCachePath(sourceId, normalized)
        val name = normalized.substringAfterLast('/')
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        return cachePath(sourceId, parent, name, Kind.Page)
    }

    fun thumbCachePath(sourceId: Long, remoteRelativeFile: String): Path {
        val normalized = remoteRelativeFile.replace('\\', '/').trimStart('/')
        val key = "davthumb:$sourceId:$normalized@$THUMB_DISK_EDGE.v$THUMB_FORMAT_VERSION"
        return thumbRoot / OriginDiskCache.thumbFileName(sha256Hex(key))
    }

    /** WebP if present, else leftover JPEG of the same hash. Disk probe — not for main. */
    fun cachedThumbIfPresent(sourceId: Long, remoteRelativeFile: String): Path? {
        val hit = OriginDiskCache.existingThumb(thumbCachePath(sourceId, remoteRelativeFile)) ?: return null
        markPresent(hit)
        touch(hit)
        return hit
    }

    /**
     * Fast cache presence check.
     * - **Main**: memory only (no File I/O). May be stale after trim.
     * - **Background**: always re-probes disk ([isCachedOnDisk]).
     */
    fun isCached(path: Path): Boolean {
        if (Looper.getMainLooper().isCurrentThread) {
            return knownPresent.contains(path.toString())
        }
        return isCachedOnDisk(path)
    }

    /**
     * Authoritative disk probe — never trusts [knownPresent] alone
     * (LRU can delete full page files after cover gen).
     */
    fun isCachedOnDisk(path: Path): Boolean {
        val key = path.toString()
        val f = File(key)
        val ok = f.isFile && f.length() > 0L
        if (ok) knownPresent.add(key) else knownPresent.remove(key)
        return ok
    }

    fun markPresent(path: Path) {
        knownPresent.add(path.toString())
    }

    fun markAbsent(path: Path) {
        val key = path.toString()
        knownPresent.remove(key)
        pathLocks.remove(key)
        val f = File(key)
        knownPresent.remove(f.absolutePath)
        knownPresent.remove(f.path)
        pathLocks.remove(f.absolutePath)
        pathLocks.remove(f.path)
    }

    fun touch(path: Path) {
        if (Looper.getMainLooper().isCurrentThread) return
        val f = File(path.toString())
        if (f.isFile) f.setLastModified(System.currentTimeMillis())
    }

    /**
     * Browse thumb: reuse page cache if present; else RAM download → MaxEdge-only thumb.
     * New platform thumbs land as WebP; lib/HDR thumbs stay Ultra HDR JPEG. Leftover JPEGs
     * are reused until LRU. When [cacheOriginal] is true and page cache is missing, download
     * via [downloadIfNeeded] (same path + HDR convert as the reader), then encode the thumb
     * from that page file.
     */
    suspend fun ensureBrowseThumb(
        sourceId: Long,
        remoteRelativeFile: String,
        cacheOriginal: Boolean = false,
        download: suspend (OutputStream) -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        cachedThumbIfPresent(sourceId, remoteRelativeFile)?.let { return@withContext it }
        val destPath = thumbCachePath(sourceId, remoteRelativeFile)
        val pagePath = cachePathForRemoteFile(sourceId, remoteRelativeFile, Kind.Page)
        val key = destPath.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            cachedThumbIfPresent(sourceId, remoteRelativeFile)?.let { return@withContext it }
            thumbFetchSlots.withPermit {
                cachedThumbIfPresent(sourceId, remoteRelativeFile)?.let { return@withContext it }
                val name = remoteRelativeFile.substringAfterLast('/')
                ensureRootDirs()
                File(destPath.parent!!.toString()).mkdirs()
                val dest = File(key)
                val pageForThumb = resolveReaderPath(pagePath)
                if (!probeDisk(pageForThumb) && cacheOriginal) {
                    // Same path + convert pipeline as the folder-gallery reader.
                    downloadIfNeeded(pagePath, originalFileName = name, write = download)
                }
                val pageAfter = resolveReaderPath(pagePath)
                if (probeDisk(pageAfter)) {
                    try {
                        writeSubsampledThumb(
                            File(pageAfter.toString()),
                            dest,
                            THUMB_DISK_EDGE,
                            THUMB_WEBP_QUALITY,
                        )
                    } catch (e: Throwable) {
                        cachedThumbIfPresent(sourceId, remoteRelativeFile)?.let { return@withContext it }
                        throw e
                    }
                } else {
                    // No page cache: MaxEdge-only thumb (no full-page UHDR from grid browse).
                    val bos = ByteArrayOutputStream(256 * 1024)
                    download(bos)
                    val ok = HdrConvertCache.writeThumbFromBytes(
                        bytes = bos.toByteArray(),
                        destJpeg = dest,
                        maxEdge = THUMB_DISK_EDGE,
                        quality = THUMB_WEBP_QUALITY,
                        fileNameHint = name,
                    )
                    if (!ok) {
                        error("WebDAV browse thumb failed for $remoteRelativeFile")
                    }
                }
                scheduleTrim()
                cachedThumbIfPresent(sourceId, remoteRelativeFile)
                    ?: error("WebDAV browse thumb missing after write for $remoteRelativeFile")
            }
        }
    }

    /**
     * Reader page download.
     * Convert mode + lib/avif: RAM → [HdrConvertCache.finalizeNetworkBytes] (B1).
     * [Settings.readerLibDirectBitmap]: stream original like non-lib (no UHDR on download).
     */
    suspend fun downloadIfNeeded(
        path: Path,
        originalFileName: String? = null,
        write: suspend (OutputStream) -> Unit,
    ) {
        val resolved = resolveReaderPath(path)
        if (isCachedOnDisk(resolved)) {
            touch(resolved)
            return
        }
        val key = path.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            val again = resolveReaderPath(path)
            if (isCachedOnDisk(again)) {
                touch(again)
                return
            }
            ensureRootDirs()
            path.parent?.let { File(it.toString()).mkdirs() }
            val nameHint = originalFileName ?: path.name
            try {
                if (HdrConvertCache.usesNetworkLibConvert(nameHint)) {
                    val bos = ByteArrayOutputStream(1024 * 1024)
                    write(bos)
                    val finalPath = HdrConvertCache.finalizeNetworkBytes(bos.toByteArray(), path, nameHint)
                    markPresent(finalPath)
                    touch(finalPath)
                } else {
                    val tmp = File("$key.tmp.${System.nanoTime()}")
                    try {
                        FileOutputStream(tmp).use { out -> write(out) }
                        val finalPath = maybeConvertHdrDownload(tmp, path, nameHint)
                        markPresent(finalPath)
                        touch(finalPath)
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                }
            } catch (e: Throwable) {
                if (isCachedOnDisk(resolveReaderPath(path))) return
                throw e
            }
        }
        scheduleTrim()
    }

    /** Prefer Ultra HDR sibling when present (disk probe — not for main). */
    fun resolveReaderPath(path: Path): Path = HdrConvertCache.resolvePagePath(path)

    /**
     * True if [path] or its Ultra HDR sibling is on disk.
     * Cache hits bump mtime so [OriginDiskCache] LRU prefers colder pages.
     */
    fun isPageCachedOnDisk(path: Path): Boolean {
        val uhdr = HdrConvertCache.uhdrSiblingOf(path)
        return when {
            uhdr.toString() != path.toString() && isCachedOnDisk(uhdr) -> {
                touch(uhdr)
                true
            }
            isCachedOnDisk(path) -> {
                touch(path)
                true
            }
            else -> false
        }
    }

    /**
     * Page present as primary or UHDR sibling.
     * Main-safe: pure [HdrConvertCache.uhdrSiblingOf] + [isCached] (no File I/O).
     */
    fun isPageCached(path: Path): Boolean {
        val uhdr = HdrConvertCache.uhdrSiblingOf(path)
        return if (uhdr.toString() == path.toString()) {
            isCached(path)
        } else {
            isCached(uhdr) || isCached(path)
        }
    }

    private suspend fun maybeConvertHdrDownload(
        tmp: File,
        primaryPath: Path,
        originalFileName: String,
    ): Path = HdrConvertCache.finalizeNetworkDownload(tmp, primaryPath, originalFileName)

    /** @see isCachedOnDisk */
    private fun probeDisk(path: Path): Boolean = isCachedOnDisk(path)

    /**
     * Same [webdav_thumb_cache] dest/key as platform thumbs.
     * Convert-path → lib+libultrahdr; else ImageDecoder subsample.
     *
     * Suspend (no [runBlocking]) so leave-folder cancel can stop thumb encode work.
     */
    private suspend fun writeSubsampledThumb(source: File, dest: File, maxEdge: Int, quality: Int) {
        val ok = HdrConvertCache.writeThumb(
            source = source.toOkioPath(),
            dest = dest,
            maxEdge = maxEdge,
            quality = quality,
            fileNameHint = source.name,
        )
        check(ok && OriginDiskCache.existingThumb(dest) != null) {
            "thumb failed for ${source.name}"
        }
    }

    private fun scheduleTrim() {
        OriginDiskCache.scheduleTrim()
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
