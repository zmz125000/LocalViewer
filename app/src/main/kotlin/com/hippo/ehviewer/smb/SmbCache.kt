package com.hippo.ehviewer.smb

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Looper
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.OriginDiskCache
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * On-disk SMB file cache.
 *
 * - **Pages** (`smb_cache/`): full remote files for the reader. Shares the unified
 *   origin budget in [com.hippo.ehviewer.library.OriginDiskCache] (Advanced image
 *   disk cache size). Oldest files first; archives are not protected.
 * - **Browse thumbs** (`smb_thumb_cache/`): small JPEG only (long edge
 *   [THUMB_DISK_EDGE]), shared [OriginDiskCache.THUMB_BUDGET_BYTES] with other
 *   thumb stores — separate from origin budget.
 */
object SmbCache {
    enum class Kind {
        /** Reader page / full-file download. */
        Page,

        /** Browse folder-list cover (small JPEG on disk). */
        Thumb,
    }

    /**
     * Long edge of JPEGs stored for browse covers.
     * @see com.hippo.ehviewer.library.OriginDiskCache.THUMB_EDGE
     */
    const val THUMB_DISK_EDGE = OriginDiskCache.THUMB_EDGE

    /** JPEG quality for disk thumbs (small + sharp enough for list/grid). */
    private const val THUMB_JPEG_QUALITY = 85

    /**
     * Bump when thumb encode semantics change (e.g. EXIF bake-in) so old on-disk
     * thumbs are not reused with wrong orientation/size.
     */
    private const val THUMB_FORMAT_VERSION = 3

    /** Cap concurrent full-file SMB fetches for thumb generation (first paint). */
    private val thumbFetchSlots = Semaphore(3)
    /**
     * Cache roots as pure path math from [ApplicationInfo.dataDir] (string field — no disk).
     * Avoid [Context.getCacheDir] + [mkdirs] on every path resolve (main-thread StrictMode
     * when browse thumbs call [thumbCachePath] during composition).
     * Directories are created only on write paths via [ensureRootDirs].
     */
    private val pageRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/smb_cache").toOkioPath()
    }

    private val thumbRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/smb_thumb_cache").toOkioPath()
    }

    /** One lock per cache file so concurrent callers share one download/encode. */
    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    /** Paths known present after write or off-main probe — avoids main-thread File I/O. */
    private val knownPresent = ConcurrentHashMap.newKeySet<String>()

    private fun rootFor(kind: Kind): Path = when (kind) {
        Kind.Page -> pageRoot
        Kind.Thumb -> thumbRoot
    }

    /** Call only from IO write paths. */
    private fun ensureRootDirs() {
        File(pageRoot.toString()).mkdirs()
        File(thumbRoot.toString()).mkdirs()
    }

    /**
     * Reader page cache path (full remote file). Explicit 3-arg overload kept so
     * callers / inlined loaders never hit NoSuchMethodError when [Kind] defaults change.
     */
    fun cachePath(sourceId: Long, remoteRelativePath: String, fileName: String): Path =
        cachePath(sourceId, remoteRelativePath, fileName, Kind.Page)

    fun cachePath(
        sourceId: Long,
        remoteRelativePath: String,
        fileName: String,
        kind: Kind,
    ): Path {
        if (kind == Kind.Thumb) {
            val dir = remoteRelativePath.replace('\\', '/').trim('/')
            val name = fileName.replace('\\', '/').substringAfterLast('/')
            val remote = if (dir.isEmpty()) name else "$dir/$name"
            return thumbCachePath(sourceId, remote)
        }
        val dir = remoteRelativePath.replace('\\', '/').trim('/')
        val name = fileName.replace('\\', '/').substringAfterLast('/')
        val key = if (dir.isEmpty()) "$sourceId:$name" else "$sourceId:$dir/$name"
        val hash = sha256Hex(key)
        val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: "bin"
        // JXR etc. always stored as Ultra HDR JPEG (never keep original on disk).
        return pageRoot / HdrConvertCache.networkStorageName(hash, ext)
    }

    /**
     * Cache path for a full share-relative file path (`Comics/Title/001.jpg`).
     * For [Kind.Thumb] this is always a **`.jpg` small thumb**, not the original file.
     */
    fun cachePathForRemoteFile(sourceId: Long, remoteRelativeFile: String): Path =
        cachePathForRemoteFile(sourceId, remoteRelativeFile, Kind.Page)

    fun cachePathForRemoteFile(
        sourceId: Long,
        remoteRelativeFile: String,
        kind: Kind,
    ): Path {
        // Same normalization as RemoteArchiveOpen so reopen hits the same file.
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

    /** Stable path for a small JPEG browse thumb. */
    fun thumbCachePath(sourceId: Long, remoteRelativeFile: String): Path {
        val normalized = remoteRelativeFile.replace('\\', '/').trimStart('/')
        val key = "thumb:$sourceId:$normalized@$THUMB_DISK_EDGE.v$THUMB_FORMAT_VERSION"
        return thumbRoot / "${sha256Hex(key)}.jpg"
    }

    /**
     * Fast cache presence check.
     * - **Main**: memory only (no File I/O / StrictMode). May be stale after trim —
     *   IO callers must use [isCachedOnDisk] or this off-main (revalidates).
     * - **Background**: always re-probes disk and syncs [knownPresent].
     */
    fun isCached(path: Path): Boolean {
        if (Looper.getMainLooper().isCurrentThread) {
            return knownPresent.contains(path.toString())
        }
        return isCachedOnDisk(path)
    }

    /**
     * Authoritative disk probe. Prefer [Dispatchers.IO] from UI.
     * Always checks the file — never trusts [knownPresent] alone (trim can delete
     * pages after cover gen while memory still says “present” → reader ENOENT).
     */
    fun isCachedOnDisk(path: Path): Boolean {
        val key = path.toString()
        val f = File(key)
        val ok = f.isFile && f.length() > 0L
        if (ok) {
            knownPresent.add(key)
        } else {
            knownPresent.remove(key)
        }
        return ok
    }

    fun markPresent(path: Path) {
        knownPresent.add(path.toString())
    }

    fun markAbsent(path: Path) {
        val key = path.toString()
        knownPresent.remove(key)
        pathLocks.remove(key)
        // Also clear alternate absolute/path forms used by trim.
        val f = File(key)
        knownPresent.remove(f.absolutePath)
        knownPresent.remove(f.path)
        pathLocks.remove(f.absolutePath)
        pathLocks.remove(f.path)
    }

    /** Bump mtime so LRU eviction prefers colder files. No-op on main (StrictMode). */
    fun touch(path: Path) {
        if (Looper.getMainLooper().isCurrentThread) return
        val f = File(path.toString())
        if (f.isFile) f.setLastModified(System.currentTimeMillis())
    }

    /**
     * Ensure a **small JPEG** browse thumb exists for [remoteRelativeFile].
     *
     * 1. Thumb hit → touch + return (no SMB)
     * 2. Miss → ensure **full original** in page cache ([smb_cache] / [Kind.Page]) via
     *    [downloadIfNeeded] (one SMB fetch, shared with reader), then subsample + JPEG
     *    into [smb_thumb_cache]
     * 3. If page cache already has the file (e.g. reader opened first), thumb is built
     *    offline with no network
     *
     * Concurrent callers for the same thumb path share one job.
     */
    suspend fun ensureBrowseThumb(
        sourceId: Long,
        remoteRelativeFile: String,
        download: suspend (OutputStream) -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        val destPath = thumbCachePath(sourceId, remoteRelativeFile)
        if (isCachedOnDisk(destPath)) {
            touch(destPath)
            return@withContext destPath
        }
        val pagePath = cachePathForRemoteFile(sourceId, remoteRelativeFile, Kind.Page)
        val key = destPath.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (isCachedOnDisk(destPath)) {
                touch(destPath)
                return@withContext destPath
            }
            thumbFetchSlots.withPermit {
                if (isCachedOnDisk(destPath)) {
                    touch(destPath)
                    return@withContext destPath
                }
                // Full original → smb_cache (same key as reader pages for this file).
                val pageName = remoteRelativeFile.substringAfterLast('/')
                downloadIfNeeded(pagePath, originalFileName = pageName, write = download)
                val pageForThumb = resolveReaderPath(pagePath)
                if (!isCachedOnDisk(pageForThumb)) {
                    error("SMB page cache empty after download for $remoteRelativeFile")
                }
                ensureRootDirs()
                File(destPath.parent!!.toString()).mkdirs()
                val dest = File(key)
                val jpgTmp = File("$key.jpg.${System.nanoTime()}")
                try {
                    writeSubsampledJpeg(
                        File(pageForThumb.toString()),
                        jpgTmp,
                        THUMB_DISK_EDGE,
                        THUMB_JPEG_QUALITY,
                    )
                    commitTmp(jpgTmp, dest)
                    markPresent(destPath)
                    touch(destPath)
                } catch (e: Throwable) {
                    if (jpgTmp.exists()) jpgTmp.delete()
                    if (isCachedOnDisk(destPath)) return@withContext destPath
                    throw e
                } finally {
                    if (jpgTmp.exists()) jpgTmp.delete()
                }
            }
        }
        scheduleTrim()
        destPath
    }

    /**
     * Download full file into [path] if missing (reader pages).
     * Do **not** use for browse covers — use [ensureBrowseThumb].
     *
     * HDR: when [originalFileName] is JPEG XR (or sniff says convert), writes Ultra HDR
     * JPEG only — original PQ/JXR bytes are not kept in [smb_cache].
     *
     * @param originalFileName remote base name for sniff / always-convert ext detection.
     */
    suspend fun downloadIfNeeded(
        path: Path,
        originalFileName: String? = null,
        write: suspend (OutputStream) -> Unit,
    ) {
        val resolved = HdrConvertCache.resolvePagePath(path)
        // Always revalidate on disk (never skip download on stale knownPresent).
        if (isCachedOnDisk(resolved)) {
            touch(resolved)
            return
        }
        val key = path.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            val again = HdrConvertCache.resolvePagePath(path)
            if (isCachedOnDisk(again)) {
                touch(again)
                return
            }
            ensureRootDirs()
            path.parent?.let { File(it.toString()).mkdirs() }
            val dest = File(key)
            val tmp = File("$key.tmp.${System.nanoTime()}")
            try {
                FileOutputStream(tmp).use { out -> write(out) }
                val nameHint = originalFileName ?: path.name
                val finalPath = maybeConvertHdrDownload(tmp, path, nameHint)
                markPresent(finalPath)
                touch(finalPath)
            } catch (e: Throwable) {
                tmp.delete()
                if (isCachedOnDisk(HdrConvertCache.resolvePagePath(path))) return
                throw e
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
        scheduleTrim()
    }

    /**
     * Prefer Ultra HDR sibling when present (post-convert network pages).
     */
    fun resolveReaderPath(path: Path): Path = HdrConvertCache.resolvePagePath(path)

    /** True if [path] or its Ultra HDR sibling is on disk. */
    fun isPageCachedOnDisk(path: Path): Boolean = isCachedOnDisk(resolveReaderPath(path))

    fun isPageCached(path: Path): Boolean {
        val resolved = resolveReaderPath(path)
        return if (resolved == path) isCached(path) else isCached(resolved) || isCached(path)
    }

    private suspend fun maybeConvertHdrDownload(
        tmp: File,
        primaryPath: Path,
        originalFileName: String,
    ): Path = HdrConvertCache.finalizeNetworkDownload(tmp, primaryPath, originalFileName)

    /**
     * Decode [source] → small JPEG. Uses [ImageDecoder] so EXIF orientation is baked
     * in (matches Coil/reader). Long edge clamped to [maxEdge]; never upscales.
     */
    private fun writeSubsampledJpeg(
        source: File,
        destJpeg: File,
        maxEdge: Int,
        quality: Int,
    ) {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                // JPEG compress requires a software bitmap (not HARDWARE).
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Default: apply EXIF orientation; [info.size] is post-orient.
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) error("Cannot decode image bounds: ${source.name}")
                val longEdge = maxOf(w, h)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot decode image: ${source.name}", e)
        }
        try {
            FileOutputStream(destJpeg).use { out ->
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    error("JPEG compress failed for ${source.name}")
                }
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
        if (!destJpeg.isFile || destJpeg.length() == 0L) {
            error("Empty JPEG thumb for ${source.name}")
        }
    }

    private fun scheduleTrim() {
        OriginDiskCache.scheduleTrim()
    }

    private fun commitTmp(tmp: File, dest: File) {
        if (!tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            error("SMB download produced empty temp file for ${dest.name}")
        }
        if (tmp.renameTo(dest)) return
        if (dest.isFile && dest.length() > 0L) {
            tmp.delete()
            return
        }
        try {
            try {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (dest.isFile && dest.length() > 0L) return
            throw IllegalStateException("Failed to commit SMB cache for ${dest.name}", e)
        }
        if (!dest.isFile || dest.length() == 0L) {
            error("Failed to commit SMB cache for ${dest.name}")
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
