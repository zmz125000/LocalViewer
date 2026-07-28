package com.hippo.ehviewer.webdav

import android.graphics.Bitmap
import android.graphics.ImageDecoder
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

    private val pageRoot: Path
        get() = appCtx.cacheDir.resolve("webdav_cache").toOkioPath().also { it.mkdirs() }
    private val thumbRoot: Path
        get() = appCtx.cacheDir.resolve("webdav_thumb_cache").toOkioPath().also { it.mkdirs() }

    private val pathLocks = ConcurrentHashMap<String, Mutex>()
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)

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
        val normalized = remoteRelativeFile.replace('\\', '/').trimStart('/')
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

    fun isCached(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
    }

    fun touch(path: Path) {
        val f = File(path.toString())
        if (f.isFile) f.setLastModified(System.currentTimeMillis())
    }

    suspend fun ensureBrowseThumb(
        sourceId: Long,
        remoteRelativeFile: String,
        download: suspend (OutputStream) -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        val destPath = thumbCachePath(sourceId, remoteRelativeFile)
        if (isCached(destPath)) {
            touch(destPath)
            return@withContext destPath
        }
        val pagePath = cachePathForRemoteFile(sourceId, remoteRelativeFile, Kind.Page)
        val key = destPath.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (isCached(destPath)) {
                touch(destPath)
                return@withContext destPath
            }
            thumbFetchSlots.withPermit {
                if (isCached(destPath)) {
                    touch(destPath)
                    return@withContext destPath
                }
                downloadIfNeeded(pagePath, download)
                if (!isCached(pagePath)) error("WebDAV page cache empty for $remoteRelativeFile")
                destPath.parent?.mkdirs()
                val dest = File(key)
                val jpgTmp = File("$key.jpg.${System.nanoTime()}")
                try {
                    writeSubsampledJpeg(File(pagePath.toString()), jpgTmp, THUMB_DISK_EDGE, THUMB_JPEG_QUALITY)
                    commitTmp(jpgTmp, dest)
                    touch(destPath)
                } catch (e: Throwable) {
                    if (jpgTmp.exists()) jpgTmp.delete()
                    if (isCached(destPath)) return@withContext destPath
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
        if (isCached(path)) {
            touch(path)
            return
        }
        val key = path.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (isCached(path)) {
                touch(path)
                return
            }
            path.parent?.mkdirs()
            val dest = File(key)
            val tmp = File("$key.tmp.${System.nanoTime()}")
            try {
                FileOutputStream(tmp).use { out -> write(out) }
                commitTmp(tmp, dest)
                touch(path)
            } catch (e: Throwable) {
                tmp.delete()
                if (isCached(path)) return
                throw e
            }
        }
        scheduleTrim()
    }

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
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= budget) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
