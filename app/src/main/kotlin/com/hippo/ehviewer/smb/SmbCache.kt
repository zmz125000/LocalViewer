package com.hippo.ehviewer.smb

import android.os.Looper
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.OriginDiskCache
import com.hippo.ehviewer.util.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    /** Share folder/gallery thumbnail network capacity with video prefix fetches. */
    suspend fun <T> withBrowseThumbFetchSlot(block: suspend () -> T): T = thumbFetchSlots.withPermit { block() }

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
    fun cachePath(sourceId: Long, remoteRelativePath: String, fileName: String): Path = cachePath(sourceId, remoteRelativePath, fileName, Kind.Page)

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
    fun cachePathForRemoteFile(sourceId: Long, remoteRelativeFile: String): Path = cachePathForRemoteFile(sourceId, remoteRelativeFile, Kind.Page)

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
     * 1. Thumb hit → return
     * 2. If page cache already has the file (reader opened first) → MaxEdge/subsample offline
     * 3. Else download to **RAM** → [HdrConvertCache.writeThumbFromBytes] (HDR = MaxEdge only)
     *
     * The decoded JPEG **always** lands in [thumbRoot]. When [cacheOriginal] is true and page
     * cache is missing, download via [downloadIfNeeded] (same path + HDR convert as the reader),
     * then encode the thumb from that page file.
     */
    suspend fun ensureBrowseThumb(
        sourceId: Long,
        remoteRelativeFile: String,
        cacheOriginal: Boolean = false,
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
                ensureRootDirs()
                File(destPath.parent!!.toString()).mkdirs()
                val dest = File(key)
                val pageName = remoteRelativeFile.substringAfterLast('/')
                val pageForThumb = resolveReaderPath(pagePath)
                if (!isCachedOnDisk(pageForThumb) && cacheOriginal) {
                    // Same path + convert pipeline as the folder-gallery reader.
                    downloadIfNeeded(pagePath, originalFileName = pageName, write = download)
                }
                val pageAfter = resolveReaderPath(pagePath)
                if (isCachedOnDisk(pageAfter)) {
                    val jpgTmp = File("$key.jpg.${System.nanoTime()}")
                    try {
                        writeSubsampledJpeg(
                            File(pageAfter.toString()),
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
                } else {
                    // No page cache: MaxEdge-only thumb (no full-page UHDR from grid browse).
                    val bos = ByteArrayOutputStream(256 * 1024)
                    download(bos)
                    val ok = HdrConvertCache.writeThumbFromBytes(
                        bytes = bos.toByteArray(),
                        destJpeg = dest,
                        maxEdge = THUMB_DISK_EDGE,
                        quality = THUMB_JPEG_QUALITY,
                        fileNameHint = pageName,
                    )
                    if (!ok || !dest.isFile || dest.length() == 0L) {
                        error("SMB browse thumb failed for $remoteRelativeFile")
                    }
                    markPresent(destPath)
                    touch(destPath)
                }
                scheduleTrim()
                destPath
            }
        }
    }

    /**
     * Download full file into [path] if missing (reader pages).
     * Do **not** use for browse covers — use [ensureBrowseThumb].
     *
     * Lib/avif + convert mode (B1): download to RAM → classify → UHDR `.jpg` only.
     * [Settings.readerLibDirectBitmap]: same as non-lib — stream original to page cache
     * (reader [LibDirectDecode] presents Bitmap; no UHDR encode on download).
     *
     * @param originalFileName remote base name for sniff / pipeline routing.
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
                if (isCachedOnDisk(HdrConvertCache.resolvePagePath(path))) return
                throw e
            }
        }
        scheduleTrim()
    }

    /**
     * Prefer Ultra HDR sibling when present (post-convert network pages).
     * Disk probe — not for main thread.
     */
    fun resolveReaderPath(path: Path): Path = HdrConvertCache.resolvePagePath(path)

    /** True if [path] or its Ultra HDR sibling is on disk. */
    fun isPageCachedOnDisk(path: Path): Boolean {
        val uhdr = HdrConvertCache.uhdrSiblingOf(path)
        return (uhdr.toString() != path.toString() && isCachedOnDisk(uhdr)) || isCachedOnDisk(path)
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

    /**
     * Decode [source] → small JPEG at [destJpeg] (same [smb_thumb_cache] key as always).
     * Convert-path formats: native decode + libultrahdr; else ImageDecoder subsample.
     */
    private fun writeSubsampledJpeg(
        source: File,
        destJpeg: File,
        maxEdge: Int,
        quality: Int,
    ) {
        val ok = runBlocking {
            HdrConvertCache.writeThumbJpeg(
                source = source.toOkioPath(),
                destJpeg = destJpeg,
                maxEdge = maxEdge,
                quality = quality,
                fileNameHint = source.name,
            )
        }
        if (!ok || !destJpeg.isFile || destJpeg.length() == 0L) {
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
