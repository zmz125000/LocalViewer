package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.read
import com.hippo.ehviewer.jni.convertAvifBytesToUltraHdr
import com.hippo.ehviewer.jni.convertAvifBytesToUltraHdrMaxEdge
import com.hippo.ehviewer.jni.convertJxlBytesToUltraHdr
import com.hippo.ehviewer.jni.convertJxlBytesToUltraHdrMaxEdge
import com.hippo.ehviewer.jni.convertJxrBytesToUltraHdr
import com.hippo.ehviewer.jni.convertJxrBytesToUltraHdrMaxEdge
import com.hippo.ehviewer.jni.convertJxrToUltraHdr
import com.hippo.ehviewer.jni.decodeJxlSdrRgba8
import com.hippo.ehviewer.jni.decodeJxrSdrRgba8
import com.hippo.ehviewer.jni.probeAvifHdrKind
import com.hippo.ehviewer.library.OriginDiskCache
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Lib still codecs (JXR / JXL / absolute PQ-AVIF) and Ultra HDR convert cache.
 *
 * ## HDR content only → Ultra HDR JPEG
 * Full-pixel scan + libultrahdr → [localRoot] / network sibling `.jpg`.
 *
 * ## SDR lib formats → no UHDR jpg cache
 * Keep original file (network caches origin). Decode via [decodeLibSdrBitmap] for
 * reader/thumbs (plain RGBA → Bitmap / regular JPEG thumb).
 *
 * Platform formats (HEIC, gain-map AVIF, JPEG…) never enter this convert path.
 */
object HdrConvertCache {
    private const val TAG = "HdrConvert"
    private const val THUMB_JPEG_QUALITY = 85

    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    /** Derived Ultra HDR for local files (user originals untouched). */
    private val localRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/hdr_ultrahdr").toOkioPath()
    }

    fun ensureLocalRoot() {
        File(localRoot.toString()).mkdirs()
    }

    /**
     * Converted Ultra HDR path for a network/extract page cache file.
     * `…/deadbeef.avif` → `…/deadbeef.jpg`; already-`*.jpg` primary stays itself.
     */
    fun uhdrSiblingOf(cachePath: Path): Path {
        val hash = cachePath.name.substringBefore('.')
        return cachePath.parent!! / "$hash.jpg"
    }

    /**
     * Network page cache name. Always keeps the original extension (SDR JXL/JXR stay
     * `hash.jxl` / `hash.jxr`). After HDR convert, [resolvePagePath] prefers sibling `.jpg`.
     */
    fun networkStorageName(hash: String, originalExt: String): String {
        val ext = originalExt.lowercase().removePrefix(".").ifEmpty { "bin" }
        return "$hash.$ext"
    }

    /**
     * Prefer converted Ultra HDR sibling when present; else [primary] (including SDR originals).
     */
    fun resolvePagePath(primary: Path): Path {
        val uhdr = uhdrSiblingOf(primary)
        if (uhdr.toString() != primary.toString() && isPresent(uhdr)) return uhdr
        return primary
    }

    fun isPresent(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
    }

    fun localDerivedPath(source: Path): Path {
        val meta = source.metadataOrNull()
        val mtime = meta?.lastModifiedAtMillis ?: 0L
        val size = meta?.size ?: 0L
        val key = "local:${source}:${mtime}:$size"
        return localRoot / "${sha256Hex(key)}.jpg"
    }

    /**
     * Ensure HDR lib sources are available as Ultra HDR JPEG for Coil.
     * **SDR lib formats return [source] unchanged** (no UHDR cache).
     */
    suspend fun ensureReadable(source: Path, fileNameHint: String = source.name): Path =
        withContext(Dispatchers.IO) {
            val sniff = sniffHdrPath(source, fileNameHint)
            if (!sniff.needsUhdrConvert) {
                return@withContext source
            }
            when (sniff.kind) {
                HdrKind.JpegXr -> ensureJxrConverted(source)
                HdrKind.AbsolutePqHlg -> ensureAvifPqConverted(source)
                HdrKind.JpegXl -> ensureJxlConverted(source)
                else -> source
            }
        }

    /**
     * Decode lib still (JXR/JXL) as SDR [Bitmap] for reader/display. No disk UHDR cache.
     * @param maxEdge ≤ 0 full resolution long edge.
     */
    suspend fun decodeLibSdrBitmap(
        source: Path,
        fileNameHint: String = source.name,
        maxEdge: Int = 0,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = runCatching { source.read { readByteArray() } }.getOrNull() ?: return@withContext null
        decodeLibSdrBitmap(bytes, fileNameHint, maxEdge)
    }

    fun decodeLibSdrBitmap(bytes: ByteArray, fileNameHint: String, maxEdge: Int = 0): Bitmap? {
        if (bytes.isEmpty()) return null
        val edge = if (maxEdge > 0) maxEdge.coerceIn(64, 8192) else 0
        val outWh = IntArray(2)
        val rgba = when {
            isJpegXlName(fileNameHint) || isJpegXlMagic(bytes) ->
                decodeJxlSdrRgba8(bytes, edge, outWh)
            isJpegXrName(fileNameHint) || isJpegXrMagic(bytes) ->
                decodeJxrSdrRgba8(bytes, edge, outWh)
            else -> {
                // Sniff kind from bytes
                val sniff = sniffHdr(bytes, bytes.size, fileNameHint)
                when (sniff.kind) {
                    HdrKind.JpegXl -> decodeJxlSdrRgba8(bytes, edge, outWh)
                    HdrKind.JpegXr -> decodeJxrSdrRgba8(bytes, edge, outWh)
                    else -> null
                }
            }
        } ?: return null
        val w = outWh[0]
        val h = outWh[1]
        if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            bmp
        }.getOrNull()
    }

    /**
     * Write long-edge [maxEdge] JPEG thumb into caller-owned [destJpeg] (same folder/key).
     *
     * Non-system (lib) formats — same **content** policy as full pages:
     * - **SDR** lib (JXR/JXL): same as full pic — keep original semantics, lib decode →
     *   plain SDR JPEG (no Ultra HDR encode / no peak scan).
     * - **HDR** lib: lib decode + scale → libultrahdr with **fixed MaxCLL 1000 nits**
     *   (skips full-frame p99.99 pixel scan used for full pages).
     * - Platform: ImageDecoder subsample
     */
    suspend fun writeThumbJpeg(
        source: Path,
        destJpeg: File,
        maxEdge: Int = OriginDiskCache.THUMB_EDGE,
        quality: Int = THUMB_JPEG_QUALITY,
        fileNameHint: String = source.name,
    ): Boolean = withContext(Dispatchers.IO) {
        if (destJpeg.isFile && destJpeg.length() > 0L) return@withContext true
        val edge = maxEdge.coerceIn(64, 2048)
        val sniff = sniffHdrPath(source, fileNameHint)
        val ok = when {
            sniff.needsUhdrConvert -> writeConvertThumb(source, destJpeg, edge, fileNameHint, sniff.kind)
            sniff.needsLibDecode && sniff.kind != HdrKind.AbsolutePqHlg ->
                writeLibSdrThumb(source, destJpeg, edge, quality, fileNameHint)
            else -> writePlatformThumb(source, destJpeg, edge, quality)
        }
        if (ok && destJpeg.isFile && destJpeg.length() > 0L) {
            OriginDiskCache.scheduleTrim()
            true
        } else {
            false
        }
    }

    /**
     * Local Coil covers: HDR → Ultra HDR derived file; SDR lib → original (decoder path);
     * platform → original.
     */
    suspend fun ensureCoverSource(source: Path, fileNameHint: String = source.name): Path =
        ensureReadable(source, fileNameHint)

    private suspend fun writeLibSdrThumb(
        source: Path,
        destJpeg: File,
        maxEdge: Int,
        quality: Int,
        fileNameHint: String,
    ): Boolean {
        val bmp = decodeLibSdrBitmap(source, fileNameHint, maxEdge) ?: return false
        return try {
            destJpeg.parentFile?.mkdirs()
            val tmp = File("${destJpeg.absolutePath}.tmp.${System.nanoTime()}")
            FileOutputStream(tmp).use { out ->
                check(bmp.compress(Bitmap.CompressFormat.JPEG, quality, out))
            }
            commitTmp(tmp, destJpeg)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "writeLibSdrThumb failed $fileNameHint", e)
            false
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun isJpegXlName(name: String) =
        FileUtils.getExtensionFromFilename(name)?.equals("jxl", true) == true

    private fun isJpegXrName(name: String): Boolean {
        val e = FileUtils.getExtensionFromFilename(name)?.lowercase()
        return e == "jxr" || e == "wdp" || e == "hdp"
    }

    private fun isJpegXlMagic(bytes: ByteArray): Boolean {
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xff) == 0xff &&
            (bytes[1].toInt() and 0xff) == 0x0a
        ) {
            return true
        }
        return bytes.size >= 12 &&
            bytes[4] == 'J'.code.toByte() &&
            bytes[5] == 'X'.code.toByte() &&
            bytes[6] == 'L'.code.toByte() &&
            bytes[7] == ' '.code.toByte()
    }

    private fun isJpegXrMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            (bytes[2].toInt() and 0xff) == 0xbc &&
            (bytes[3].toInt() and 0xff) == 0x01

    private suspend fun writeConvertThumb(
        source: Path,
        destJpeg: File,
        maxEdge: Int,
        fileNameHint: String,
        kind: HdrKind,
    ): Boolean {
        val bytes = runCatching { source.read { readByteArray() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Log.e(TAG, "writeConvertThumb: unreadable $fileNameHint")
            return false
        }
        return when (kind) {
            HdrKind.JpegXr -> convertBytesMaxEdge(bytes, destJpeg, maxEdge, ::convertJxrBytesToUltraHdrMaxEdge)
            HdrKind.JpegXl -> convertBytesMaxEdge(bytes, destJpeg, maxEdge, ::convertJxlBytesToUltraHdrMaxEdge)
            HdrKind.AbsolutePqHlg -> convertBytesMaxEdge(bytes, destJpeg, maxEdge, ::convertAvifBytesToUltraHdrMaxEdge)
            else -> false
        }
    }

    private suspend fun convertBytesMaxEdge(
        input: ByteArray,
        output: File,
        maxEdge: Int,
        native: (ByteArray, String, Int) -> Int,
    ): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (input.isEmpty()) return@withContext false
        val lockKey = output.absolutePath
        val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
        mutex.withLock {
            if (output.isFile && output.length() > 0L) return@withLock true
            output.parentFile?.mkdirs()
            val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
            try {
                val code = native(input, tmp.absolutePath, maxEdge)
                if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                    Log.e(TAG, "convert MaxEdge failed code=$code out=${tmp.name} in=${input.size}b")
                    tmp.delete()
                    return@withLock false
                }
                commitTmp(tmp, output)
                true
            } catch (e: Throwable) {
                Log.e(TAG, "convert MaxEdge exception", e)
                tmp.delete()
                false
            }
        }
    }

    private fun writePlatformThumb(source: Path, destJpeg: File, maxEdge: Int, quality: Int): Boolean {
        return runCatching {
            val srcFile = File(source.toString())
            // Physical path preferred; SAF content:// needs ImageDecoder.createSource(context, uri)
            // but local/archive thumbs always pass real File paths after extract.
            if (!srcFile.isFile || srcFile.length() <= 0L) {
                // Fallback: may still be a plain path string for Okio SAF — try decode via path string.
                Log.e(TAG, "writePlatformThumb: missing file $source")
                return false
            }
            destJpeg.parentFile?.mkdirs()
            val tmp = File("${destJpeg.absolutePath}.tmp.${System.nanoTime()}")
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(srcFile)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) error("bad bounds")
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
                FileOutputStream(tmp).use { out ->
                    check(decoded.compress(Bitmap.CompressFormat.JPEG, quality, out))
                }
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
            commitTmp(tmp, destJpeg)
            true
        }.onFailure {
            Log.e(TAG, "writePlatformThumb failed $source", it)
        }.getOrDefault(false)
    }

    /**
     * Convert JPEG XR at physical [input] → Ultra HDR at [output] (atomic).
     * @return true if [output] is ready
     */
    suspend fun convertJxrFile(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (!input.isFile || input.length() <= 0L) {
            Log.e(TAG, "convertJxrFile: missing input ${input.absolutePath}")
            return@withContext false
        }
        // Prefer memory path (same as SAF) so jxrlib uses CreateStreamFromMemory + setup_full_frame.
        val bytes = runCatching { input.readBytes() }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            return@withContext convertJxrBytes(bytes, output)
        }
        convertJxrViaPath(input.absolutePath, output)
    }

    /**
     * Convert JXR bytes → Ultra HDR JPEG at [output].
     * No intermediate .jxr on disk (branch `hdr` local-folder path).
     */
    suspend fun convertJxrBytes(input: ByteArray, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (input.isEmpty()) return@withContext false
        val lockKey = output.absolutePath
        val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
        mutex.withLock {
            if (output.isFile && output.length() > 0L) return@withLock true
            output.parentFile?.mkdirs()
            val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
            try {
                val code = convertJxrBytesToUltraHdr(input, tmp.absolutePath)
                if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                    Log.e(TAG, "convertJxrBytesToUltraHdr failed code=$code out=${tmp.name} in=${input.size}b")
                    tmp.delete()
                    return@withLock false
                }
                commitTmp(tmp, output)
                OriginDiskCache.scheduleTrim()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "convertJxrBytes exception", e)
                tmp.delete()
                false
            }
        }
    }

    private suspend fun convertJxrViaPath(inputPath: String, output: File): Boolean =
        withContext(Dispatchers.IO) {
            if (output.isFile && output.length() > 0L) return@withContext true
            val lockKey = output.absolutePath
            val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
            mutex.withLock {
                if (output.isFile && output.length() > 0L) return@withLock true
                output.parentFile?.mkdirs()
                val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
                try {
                    val code = convertJxrToUltraHdr(inputPath, tmp.absolutePath)
                    if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                        Log.e(TAG, "convertJxrToUltraHdr failed code=$code in=$inputPath")
                        tmp.delete()
                        return@withLock false
                    }
                    commitTmp(tmp, output)
                    OriginDiskCache.scheduleTrim()
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "convertJxrViaPath exception", e)
                    tmp.delete()
                    false
                }
            }
        }

    /**
     * Commit a network page download.
     * - **SDR / platform / unknown lib SDR:** keep original at [primaryPath] (no UHDR jpg).
     * - **Confirmed HDR (JXR/JXL/PQ-AVIF):** convert to Ultra HDR sibling `.jpg`, drop original.
     * Hard-fail only when confirmed HDR convert fails (cannot present HDR without UHDR).
     */
    suspend fun finalizeNetworkDownload(
        tmp: File,
        primaryPath: Path,
        originalFileName: String,
    ): Path = withContext(Dispatchers.IO) {
        val sniff = sniffHdr(tmp, fileNameHint = originalFileName)
        if (!sniff.needsUhdrConvert) {
            // SDR jxl/jxr and all platform formats: cache the original bytes only.
            commitTmp(tmp, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        val outPath = uhdrSiblingOf(primaryPath)
        val outFile = File(outPath.toString())
        val ok = when (sniff.kind) {
            HdrKind.JpegXr -> convertJxrFile(tmp, outFile)
            HdrKind.AbsolutePqHlg -> convertAvifFile(tmp, outFile)
            HdrKind.JpegXl -> {
                val bytes = runCatching { tmp.readBytes() }.getOrNull()
                if (bytes != null) convertJxlBytes(bytes, outFile) else false
            }
            else -> false
        }
        if (ok) {
            tmp.delete()
            val primary = File(primaryPath.toString())
            if (primary.absolutePath != outFile.absolutePath) primary.delete()
            return@withContext outPath
        }
        // Confirmed HDR must convert; soft-fail only for AVIF probe edge cases.
        if (sniff.kind == HdrKind.AbsolutePqHlg) {
            commitTmp(tmp, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        tmp.delete()
        error("HDR convert failed for $originalFileName")
    }

    private suspend fun ensureJxrConverted(source: Path): Path {
        val dest = localDerivedPath(source)
        ensureLocalRoot()
        val destFile = File(dest.toString())
        if (destFile.isFile && destFile.length() > 0L) return dest

        // Okio read → bytes → CreateStreamFromMemory (no temp original).
        val bytes = runCatching {
            source.read { readByteArray() }
        }.onFailure {
            Log.e(TAG, "read JXR failed: $source", it)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            error("JPEG XR source unreadable: ${source.name}")
        }
        if (convertJxrBytes(bytes, destFile)) return dest
        error("JPEG XR → Ultra HDR convert failed: ${source.name}")
    }

    private suspend fun ensureAvifPqConverted(source: Path): Path {
        val dest = localDerivedPath(source)
        ensureLocalRoot()
        val destFile = File(dest.toString())
        if (destFile.isFile && destFile.length() > 0L) return dest

        val bytes = runCatching {
            source.read { readByteArray() }
        }.onFailure {
            Log.e(TAG, "read AVIF failed: $source", it)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            error("AVIF source unreadable: ${source.name}")
        }
        // HEIC/HEIF (HEVC) must not go through libavif — platform ImageDecoder only.
        val ext = source.name.substringAfterLast('.', "").lowercase()
        if (isHeicImageExtension(ext)) {
            Log.i(TAG, "HEIC/HEIF → platform path (not libavif): ${source.name}")
            return source
        }
        // If native probe says gain-map, leave for platform ImageDecoder.
        when (probeAvifHdrKind(bytes)) {
            1 -> {
                Log.i(TAG, "AVIF gain-map → platform path: ${source.name}")
                return source
            }
            0 -> {
                // Not AVIF / probe fail — try convert only for known PQ sniff.
            }
        }
        if (convertAvifBytes(bytes, destFile)) return dest
        // libavif failed (often mis-sniffed HEIC): fall back to original for Coil/ImageDecoder.
        Log.w(TAG, "AVIF PQ convert failed, platform fallback: ${source.name}")
        return source
    }

    suspend fun convertAvifFile(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (isHeicImageExtension(input.extension)) return@withContext false
        val bytes = runCatching { input.readBytes() }.getOrNull() ?: return@withContext false
        when (probeAvifHdrKind(bytes)) {
            1 -> return@withContext false // gain-map: keep original for platform
        }
        convertAvifBytes(bytes, output)
    }

    suspend fun convertAvifBytes(input: ByteArray, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (input.isEmpty()) return@withContext false
        val lockKey = output.absolutePath
        val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
        mutex.withLock {
            if (output.isFile && output.length() > 0L) return@withLock true
            output.parentFile?.mkdirs()
            val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
            try {
                val code = convertAvifBytesToUltraHdr(input, tmp.absolutePath)
                if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                    Log.e(TAG, "convertAvifBytesToUltraHdr failed code=$code in=${input.size}b")
                    tmp.delete()
                    return@withLock false
                }
                commitTmp(tmp, output)
                OriginDiskCache.scheduleTrim()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "convertAvifBytes exception", e)
                tmp.delete()
                false
            }
        }
    }

    private suspend fun ensureJxlConverted(source: Path): Path {
        val dest = localDerivedPath(source)
        ensureLocalRoot()
        val destFile = File(dest.toString())
        if (destFile.isFile && destFile.length() > 0L) return dest

        val bytes = runCatching {
            source.read { readByteArray() }
        }.onFailure {
            Log.e(TAG, "read JXL failed: $source", it)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            error("JPEG XL source unreadable: ${source.name}")
        }
        if (convertJxlBytes(bytes, destFile)) return dest
        error("JPEG XL → Ultra HDR convert failed: ${source.name}")
    }

    suspend fun convertJxlBytes(input: ByteArray, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (input.isEmpty()) return@withContext false
        val lockKey = output.absolutePath
        val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
        mutex.withLock {
            if (output.isFile && output.length() > 0L) return@withLock true
            output.parentFile?.mkdirs()
            val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
            try {
                val code = convertJxlBytesToUltraHdr(input, tmp.absolutePath)
                if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                    Log.e(TAG, "convertJxlBytesToUltraHdr failed code=$code in=${input.size}b")
                    tmp.delete()
                    return@withLock false
                }
                commitTmp(tmp, output)
                OriginDiskCache.scheduleTrim()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "convertJxlBytes exception", e)
                tmp.delete()
                false
            }
        }
    }

    private fun commitTmp(tmp: File, dest: File) {
        if (!tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            error("Empty Ultra HDR temp for ${dest.name}")
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
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (dest.isFile && dest.length() > 0L) return
            throw IllegalStateException("Failed to commit Ultra HDR for ${dest.name}", e)
        }
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }
}
