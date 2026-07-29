package com.hippo.ehviewer.webdav

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Looper
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * - Pages: `webdav_cache/` full files
 * - Thumbs: `webdav_thumb_cache/` small JPEG
 */
object WebDavCache {
    enum class Kind { Page, Thumb }

    const val THUMB_DISK_EDGE = 768
    private const val THUMB_JPEG_QUALITY = 85
    private const val THUMB_FORMAT_VERSION = 1
    private const val THUMB_BUDGET_BYTES = 512L * 1024L * 1024L
    private val thumbFetchSlots = Semaphore(3)

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
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)

    private fun ensureRootDirs() {
        File(pageRoot.toString()).mkdirs()
        File(thumbRoot.toString()).mkdirs()
    }

    fun cachePath(sourceId: Long, remoteRelativePath: String, fileName: String): Path =
        cachePath(sourceId, remoteRelativePath, fileName, Kind.Page)

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
            pageRoot / "$hash.$ext"
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
        return thumbRoot / "${sha256Hex(key)}.jpg"
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

    fun touch(path: Path) {
        if (Looper.getMainLooper().isCurrentThread) return
        val f = File(path.toString())
        if (f.isFile) f.setLastModified(System.currentTimeMillis())
    }

    suspend fun ensureBrowseThumb(
        sourceId: Long,
        remoteRelativeFile: String,
        download: suspend (OutputStream) -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        val destPath = thumbCachePath(sourceId, remoteRelativeFile)
        // Always on IO here — allow real disk probe.
        if (probeDisk(destPath)) {
            touch(destPath)
            return@withContext destPath
        }
        val pagePath = cachePathForRemoteFile(sourceId, remoteRelativeFile, Kind.Page)
        val key = destPath.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (probeDisk(destPath)) {
                touch(destPath)
                return@withContext destPath
            }
            thumbFetchSlots.withPermit {
                if (probeDisk(destPath)) {
                    touch(destPath)
                    return@withContext destPath
                }
                downloadIfNeeded(pagePath, download)
                if (!probeDisk(pagePath)) error("WebDAV page cache empty for $remoteRelativeFile")
                ensureRootDirs()
                File(destPath.parent!!.toString()).mkdirs()
                val dest = File(key)
                val jpgTmp = File("$key.jpg.${System.nanoTime()}")
                try {
                    writeSubsampledJpeg(File(pagePath.toString()), jpgTmp, THUMB_DISK_EDGE, THUMB_JPEG_QUALITY)
                    commitTmp(jpgTmp, dest)
                    markPresent(destPath)
                    touch(destPath)
                } catch (e: Throwable) {
                    if (jpgTmp.exists()) jpgTmp.delete()
                    if (probeDisk(destPath)) return@withContext destPath
                    throw e
                } finally {
                    if (jpgTmp.exists()) jpgTmp.delete()
                }
            }
        }
        scheduleTrim()
        destPath
    }

    suspend fun downloadIfNeeded(path: Path, write: suspend (OutputStream) -> Unit) {
        if (isCachedOnDisk(path)) {
            touch(path)
            return
        }
        val key = path.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (isCachedOnDisk(path)) {
                touch(path)
                return
            }
            ensureRootDirs()
            path.parent?.let { File(it.toString()).mkdirs() }
            val dest = File(key)
            val tmp = File("$key.tmp.${System.nanoTime()}")
            try {
                FileOutputStream(tmp).use { out -> write(out) }
                commitTmp(tmp, dest)
                markPresent(path)
                touch(path)
            } catch (e: Throwable) {
                tmp.delete()
                if (isCachedOnDisk(path)) return
                throw e
            }
        }
        scheduleTrim()
    }

    /** @see isCachedOnDisk */
    private fun probeDisk(path: Path): Boolean = isCachedOnDisk(path)

    private fun writeSubsampledJpeg(source: File, destJpeg: File, maxEdge: Int, quality: Int) {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            if (w <= 0 || h <= 0) error("Cannot decode bounds: ${source.name}")
            val longEdge = maxOf(w, h)
            if (longEdge > maxEdge) {
                val scale = maxEdge.toFloat() / longEdge
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        try {
            FileOutputStream(destJpeg).use { out ->
                check(decoded.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    "JPEG compress failed"
                }
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun commitTmp(tmp: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun scheduleTrim() {
        if (!trimScheduled.compareAndSet(false, true)) return
        trimScope.launch {
            try {
                trimToMaxSize()
            } finally {
                trimScheduled.set(false)
            }
        }
    }

    private suspend fun trimToMaxSize() = withContext(Dispatchers.IO) {
        trimLock.withLock {
            val pageBudget = Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024L * 1024L
            trimDir(File(pageRoot.toString()), pageBudget)
            trimDir(File(thumbRoot.toString()), THUMB_BUDGET_BYTES)
        }
    }

    private fun trimDir(dir: File, budget: Long) {
        if (!dir.isDirectory) return
        // Snapshot mtime/size before sort — concurrent touch() during sortBy { lastModified() }
        // mutates the comparison key mid-TimSort → "Comparison method violates its general contract".
        data class Entry(val file: File, val mtime: Long, val size: Long)
        val files = dir.listFiles()
            ?.mapNotNull { f ->
                if (!f.isFile) return@mapNotNull null
                Entry(f, f.lastModified(), f.length())
            }
            ?.sortedWith(compareBy<Entry> { it.mtime }.thenBy { it.file.name })
            ?: return
        var total = files.sumOf { it.size }
        for (e in files) {
            if (total <= budget) break
            // Keep comic archives — reopen must not re-download large zips.
            if (com.hippo.ehviewer.library.isArchiveCacheFileName(e.file.name)) continue
            if (e.file.delete()) {
                total -= e.size
                knownPresent.remove(e.file.absolutePath)
                knownPresent.remove(e.file.path)
                pathLocks.remove(e.file.absolutePath)
                pathLocks.remove(e.file.path)
            }
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
