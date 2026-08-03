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
import com.hippo.ehviewer.jni.probeAvifHdrKind
import com.hippo.ehviewer.library.OriginDiskCache
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
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
 * Converts absolute HDR / JPEG XR / JPEG XL / PQ-AVIF sources to Ultra HDR JPEG and
 * stores full-size results under [localRoot] (local/SAF) or network page caches.
 *
 * **Thumbs:** [writeThumbJpeg] writes into the **caller-owned** dest (same folder, same
 * `.jpg` key as platform thumbs — e.g. `archive_thumb`, `smb_thumb_cache`). Convert-path
 * formats use native decode + libultrahdr at [maxEdge]; everything else uses ImageDecoder.
 * There is no separate `hdr_thumbs` store.
 *
 * Supported convert path (inventory freeze):
 * - JPEG XR (`jxr`/`wdp`/`hdp`) → jxrlib
 * - JPEG XL (`jxl`) → libjxl
 * - Absolute PQ/HLG **AVIF** only → libavif (+ aom)
 * - All of the above encode to Ultra HDR JPEG via libultrahdr
 *
 * **HEIC/HEIF** (HEVC) and gain-map AVIF/JPEG: platform ImageDecoder (not converted here).
 *
 * Convert always runs independent of [com.hippo.ehviewer.Settings.readerHdrDisplay].
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
     * Page path for network caches: always-convert extensions resolve directly to `.jpg`.
     * Other types keep original extension; after convert, [resolvePagePath] prefers `.jpg`.
     */
    fun networkStorageName(hash: String, originalExt: String): String {
        val ext = originalExt.lowercase().removePrefix(".")
        return if (isHdrAlwaysConvertExtension(ext)) {
            "$hash.jpg"
        } else {
            "$hash.$ext"
        }
    }

    /**
     * Prefer converted Ultra HDR sibling when present; else [primary].
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
     * Ensure [source] is available as Ultra HDR for the reader.
     * Supports physical files and SAF/content Okio paths.
     * Independent of HDR display pref — always convert when [HdrKind.needsConvert].
     * @return path to open (converted Ultra HDR or original when no convert needed)
     */
    suspend fun ensureReadable(source: Path, fileNameHint: String = source.name): Path =
        withContext(Dispatchers.IO) {
            val sniff = sniffHdrPath(source, fileNameHint)
            if (!sniff.needsConvert) {
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
     * Write a long-edge [maxEdge] JPEG thumb of [source] into **[destJpeg]** (caller owns
     * path / cache key / folder — same as platform thumbs).
     *
     * - [HdrKind.needsConvert]: native decode → scale → libultrahdr Ultra HDR JPEG
     * - else: ImageDecoder subsample + [Bitmap.CompressFormat.JPEG]
     *
     * @return true when [destJpeg] is a non-empty file
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
        val ok = if (sniff.needsConvert) {
            writeConvertThumb(source, destJpeg, edge, fileNameHint, sniff.kind)
        } else {
            writePlatformThumb(source, destJpeg, edge, quality)
        }
        if (ok && destJpeg.isFile && destJpeg.length() > 0L) {
            OriginDiskCache.scheduleTrim()
            true
        } else {
            false
        }
    }

    /**
     * Convenience for local Coil covers: convert-path → full Ultra HDR in [localRoot]
     * (same derived store as the reader); platform formats return [source] unchanged.
     * Coil then decodes/scales — no parallel thumb key store.
     */
    suspend fun ensureCoverSource(source: Path, fileNameHint: String = source.name): Path =
        ensureReadable(source, fileNameHint)

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
     * Commit a network page download temp file: convert HDR sources to Ultra HDR JPEG
     * when needed, otherwise move [tmp] to [primaryPath].
     *
     * Always-convert types (JXR/JXL): hard-fail if convert fails (cannot serve original).
     * PQ AVIF: soft-fail keeps the original download at [primaryPath].
     * On convert success, [tmp] and the non-UHDR primary (if different) are deleted.
     *
     * Shared by SMB / WebDAV page caches.
     */
    suspend fun finalizeNetworkDownload(
        tmp: File,
        primaryPath: Path,
        originalFileName: String,
    ): Path = withContext(Dispatchers.IO) {
        val sniff = sniffHdr(tmp, fileNameHint = originalFileName)
        if (!sniff.needsConvert) {
            commitTmp(tmp, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        // Convert → Ultra HDR JPEG; do not keep original PQ/JXR/JXL for network.
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
        // Always-convert (JXR/JXL): cannot serve original to Coil.
        if (sniff.kind == HdrKind.JpegXr ||
            sniff.kind == HdrKind.JpegXl ||
            isHdrAlwaysConvertExtension(FileUtils.getExtensionFromFilename(originalFileName))
        ) {
            tmp.delete()
            error("HDR convert failed for $originalFileName")
        }
        // Soft-fail convert (e.g. mis-sniffed AVIF): keep downloaded original.
        commitTmp(tmp, File(primaryPath.toString()))
        primaryPath
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
