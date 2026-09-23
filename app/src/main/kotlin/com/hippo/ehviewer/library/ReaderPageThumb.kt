package com.hippo.ehviewer.library

import android.graphics.Bitmap
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import java.io.File
import java.io.FileOutputStream
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
 * 768px photo-grid thumbs written after a reader page decodes.
 *
 * Keyed by a stable cover identity so the reader photo grid can paint the small
 * file instead of the origin page.
 * Shares [OriginDiskCache.THUMB_BUDGET_BYTES] with other thumb stores.
 */
object ReaderPageThumb {
    private const val FORMAT_VERSION = 1

    /** Skip bitmap-fallback encode above this pixel count (ARGB copy would spike RAM). */
    private const val MAX_BITMAP_PIXELS = 16_000_000L

    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/page_thumb").toOkioPath()
    }

    private val pathLocks = ConcurrentHashMap<String, Mutex>()
    private val encodeSlots = Semaphore(1)

    fun dest(identity: String): Path = root / OriginDiskCache.thumbFileName(sha256Hex("v$FORMAT_VERSION:$identity"))

    fun isThumbPath(path: Path): Boolean = path.toString().contains("/cache/page_thumb/")

    fun find(identity: String): Path? = OriginDiskCache.existingThumb(dest(identity))

    suspend fun ensureFromFile(identity: String, source: Path): Path? = withContext(Dispatchers.IO) {
        val destPath = dest(identity)
        OriginDiskCache.existingThumb(destPath)?.let { return@withContext it }
        val src = File(source.toString())
        if (!src.isFile || src.length() <= 0L) return@withContext null
        encodeSlots.withPermit {
            OriginDiskCache.existingThumb(destPath)?.let { return@withPermit it }
            val key = destPath.toString()
            val mutex = pathLocks.getOrPut(key) { Mutex() }
            mutex.withLock {
                OriginDiskCache.existingThumb(destPath)?.let { return@withLock it }
                File(destPath.parent!!.toString()).mkdirs()
                val destFile = File(destPath.toString())
                val ok = runCatching {
                    HdrConvertCache.writeThumb(
                        source = source,
                        dest = destFile,
                        maxEdge = OriginDiskCache.THUMB_EDGE,
                        quality = OriginDiskCache.THUMB_QUALITY,
                        fileNameHint = source.name,
                    )
                }.onFailure { logcat("PageThumb", it) }.getOrDefault(false)
                OriginDiskCache.existingThumb(destPath).takeIf { ok }
            }
        }
    }

    /**
     * Fallback when the page has no on-disk source (mmap ZIP, software bitmap only).
     * HARDWARE bitmaps are refused — GPU readback hitch the reader viewport.
     */
    suspend fun ensureFromBitmap(identity: String, bitmap: Bitmap): Path? = withContext(Dispatchers.IO) {
        val destPath = dest(identity)
        OriginDiskCache.existingThumb(destPath)?.let { return@withContext it }
        if (bitmap.isRecycled) return@withContext null
        val pixels = bitmap.width.toLong().coerceAtLeast(0) * bitmap.height.toLong().coerceAtLeast(0)
        if (pixels <= 0L || pixels > MAX_BITMAP_PIXELS) return@withContext null
        encodeSlots.withPermit {
            OriginDiskCache.existingThumb(destPath)?.let { return@withPermit it }
            val key = destPath.toString()
            val mutex = pathLocks.getOrPut(key) { Mutex() }
            mutex.withLock {
                OriginDiskCache.existingThumb(destPath)?.let { return@withLock it }
                File(destPath.parent!!.toString()).mkdirs()
                val destFile = File(destPath.toString())
                val tmp = File("${destFile.absolutePath}.tmp.${System.nanoTime()}")
                var software: Bitmap? = null
                var scaled: Bitmap? = null
                try {
                    if (bitmap.config == Bitmap.Config.HARDWARE) return@withLock null
                    software = bitmap
                    if (software == null || software.isRecycled) return@withLock null
                    val w = software.width
                    val h = software.height
                    val longEdge = maxOf(w, h)
                    val toEncode = if (longEdge > OriginDiskCache.THUMB_EDGE) {
                        val scale = OriginDiskCache.THUMB_EDGE.toFloat() / longEdge
                        Bitmap.createScaledBitmap(
                            software,
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1),
                            true,
                        ).also { scaled = it }
                    } else {
                        software
                    }
                    FileOutputStream(tmp).use { out ->
                        check(toEncode.compress(Bitmap.CompressFormat.WEBP_LOSSY, OriginDiskCache.THUMB_QUALITY, out))
                    }
                    if (!tmp.renameTo(destFile) && !(destFile.isFile && destFile.length() > 0L)) {
                        tmp.copyTo(destFile, overwrite = true)
                        tmp.delete()
                    }
                    OriginDiskCache.scheduleTrim()
                    OriginDiskCache.existingThumb(destPath)
                } catch (e: Throwable) {
                    logcat("PageThumb", e)
                    tmp.delete()
                    null
                } finally {
                    val scaledBm = scaled
                    val softwareBm = software
                    if (scaledBm != null && scaledBm !== softwareBm && !scaledBm.isRecycled) {
                        scaledBm.recycle()
                    }
                    if (softwareBm != null && softwareBm !== bitmap && !softwareBm.isRecycled) {
                        softwareBm.recycle()
                    }
                    if (tmp.exists() && tmp.absolutePath != destFile.absolutePath) tmp.delete()
                }
            }
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
