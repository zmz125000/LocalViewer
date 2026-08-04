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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * ## Network B1 pipeline (lib candidates)
 * Download to **RAM** → single global convert slot → commit only `.jpg` (or original for SDR).
 * No discarded multi-30MB originals on disk.
 *
 * ## SDR lib formats → no UHDR jpg cache
 * Keep original file (network caches origin). Decode via [decodeLibSdrBitmap] for
 * reader/thumbs (plain RGBA → Bitmap / regular JPEG thumb).
 *
 * Platform formats (HEIC, gain-map AVIF, JPEG…) never enter this convert path.
 *
 * Public surface: [ensureDisplayFile], [ensureUhdrFromBytes], [finalizeNetworkBytes],
 * [finalizeNetworkDownload], [writeThumbJpeg] / [writeThumbFromBytes], [decodeLibSdrBitmap].
 */
object HdrConvertCache {
    private const val TAG = "HdrConvert"
    private const val THUMB_JPEG_QUALITY = 85

    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    /** At most one full/thumb UHDR convert in flight (CPU + peak RAM). */
    private val convertSlots = Semaphore(3)

    /**
     * Exts that use the RAM → classify → convert pipeline on network download
     * (avoids writing full original then discarding it).
     */
    fun isRamPipelineCandidate(fileName: String): Boolean {
        val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase()
        return isLibStillExtension(ext) || ext == "avif"
    }

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
     *
     * **Disk I/O** ([File.isFile]) — call off main only. Main-thread presence checks must use
     * pure [uhdrSiblingOf] + an in-memory set (see Smb/WebDav `isPageCached`).
     */
    fun resolvePagePath(primary: Path): Path {
        val uhdr = uhdrSiblingOf(primary)
        if (uhdr.toString() != primary.toString() && isPresent(uhdr)) return uhdr
        return primary
    }

    /** Disk presence probe — not StrictMode-safe on main. */
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
    suspend fun ensureDisplayFile(source: Path, fileNameHint: String = source.name): Path =
        withContext(Dispatchers.IO) {
            val route = classifyPath(source, fileNameHint)
            if (!route.needsUhdr) return@withContext source
            val lib = route as StillRoute.Lib
            ensureUhdrLocal(source, lib.codec)
        }

    /** Alias for [ensureDisplayFile]. */
    suspend fun ensureReadable(source: Path, fileNameHint: String = source.name): Path =
        ensureDisplayFile(source, fileNameHint)

    /** Local Coil covers: Coil-ready path (UHDR or SDR jpeg for lib, else original). */
    suspend fun ensureCoverSource(source: Path, fileNameHint: String = source.name): Path =
        ensureCoilReady(source, fileNameHint)

    /**
     * Reader/cache chokepoint: any path → **Coil / ImageDecoder-ready** file.
     * - Platform / gain-map → [source]
     * - Lib HDR → Ultra HDR JPEG under [localRoot]
     * - Lib SDR (JXR/JXL) → plain JPEG under [localRoot] (no gain map)
     */
    suspend fun ensureCoilReady(source: Path, fileNameHint: String = source.name): Path =
        withContext(Dispatchers.IO) {
            val ext = FileUtils.getExtensionFromFilename(fileNameHint)?.lowercase()
                ?: FileUtils.getExtensionFromFilename(source.name)?.lowercase()
            if (!isHdrConvertCandidateExtension(ext)) return@withContext source
            // Already a derived Ultra HDR / SDR jpeg in our cache.
            if (source.name.endsWith(".jpg", ignoreCase = true) &&
                source.toString().contains("hdr_ultrahdr")
            ) {
                return@withContext source
            }
            val route = classifyPath(source, fileNameHint)
            when {
                route.needsUhdr -> {
                    val lib = route as StillRoute.Lib
                    ensureUhdrLocal(source, lib.codec)
                }
                route.isLibSdr -> ensureSdrJpeg(source, fileNameHint)
                else -> source
            }
        }

    /**
     * In-memory archive pages → Coil-ready file on disk.
     * Platform bytes written to a hashed temp for a stable PathSource; lib HDR/SDR converted.
     */
    suspend fun ensureCoilReadyFromBytes(bytes: ByteArray, fileNameHint: String): Path =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) error("empty image buffer: $fileNameHint")
            val route = classify(bytes, bytes.size, fileNameHint)
            when {
                route.needsUhdr -> ensureUhdrFromBytes(bytes, fileNameHint)
                route.isLibSdr -> ensureSdrJpegFromBytes(bytes, fileNameHint)
                else -> {
                    // Platform / gain-map: materialize so PathSource open is uniform.
                    ensureLocalRoot()
                    val ext = guessPlatformExt(bytes, fileNameHint)
                    val dest = localRoot / "${sha256HexBytes(bytes)}.$ext"
                    val f = File(dest.toString())
                    if (!f.isFile || f.length() == 0L) {
                        writeBytesAtomic(bytes, f)
                    }
                    dest
                }
            }
        }

    private fun guessPlatformExt(bytes: ByteArray, fileNameHint: String): String {
        FileUtils.getExtensionFromFilename(fileNameHint)?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "bin" }
            ?.let { return it }
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xff) == 0xff &&
            (bytes[1].toInt() and 0xff) == 0xd8
        ) {
            return "jpg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        ) {
            return "png"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
        ) {
            return "webp"
        }
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            return "avif"
        }
        return "jpg"
    }

    private suspend fun ensureSdrJpeg(source: Path, fileNameHint: String): Path {
        ensureLocalRoot()
        val meta = source.metadataOrNull()
        val mtime = meta?.lastModifiedAtMillis ?: 0L
        val size = meta?.size ?: 0L
        val dest = localRoot / "${sha256Hex("sdr:$source:$mtime:$size")}.sdr.jpg"
        val destFile = File(dest.toString())
        if (destFile.isFile && destFile.length() > 0L) return dest
        val bmp = decodeLibSdrBitmap(source, fileNameHint, maxEdge = 0)
            ?: error("Lib SDR decode failed: $fileNameHint")
        try {
            writeBitmapJpeg(bmp, destFile, quality = 92)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        OriginDiskCache.scheduleTrim()
        return dest
    }

    private suspend fun ensureSdrJpegFromBytes(bytes: ByteArray, fileNameHint: String): Path {
        ensureLocalRoot()
        val dest = localRoot / "${sha256HexBytes(bytes)}.sdr.jpg"
        val destFile = File(dest.toString())
        if (destFile.isFile && destFile.length() > 0L) return dest
        val bmp = decodeLibSdrBitmap(bytes, fileNameHint, maxEdge = 0)
            ?: error("Lib SDR decode failed: $fileNameHint")
        try {
            writeBitmapJpeg(bmp, destFile, quality = 92)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        OriginDiskCache.scheduleTrim()
        return dest
    }

    private fun writeBitmapJpeg(bmp: Bitmap, dest: File, quality: Int) {
        dest.parentFile?.mkdirs()
        val tmp = File("${dest.absolutePath}.tmp.${System.nanoTime()}")
        FileOutputStream(tmp).use { out ->
            check(bmp.compress(Bitmap.CompressFormat.JPEG, quality, out))
        }
        commitTmp(tmp, dest)
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
        val codec = when {
            isJpegXlName(fileNameHint) || isJpegXlMagic(bytes) -> LibCodec.Jxl
            isJpegXrName(fileNameHint) || isJpegXrMagic(bytes) -> LibCodec.Jxr
            else -> {
                val route = classify(bytes, bytes.size, fileNameHint)
                (route as? StillRoute.Lib)?.codec?.takeIf { it == LibCodec.Jxl || it == LibCodec.Jxr }
            }
        } ?: return null
        val rgba = when (codec) {
            LibCodec.Jxl -> decodeJxlSdrRgba8(bytes, edge, outWh)
            LibCodec.Jxr -> decodeJxrSdrRgba8(bytes, edge, outWh)
            LibCodec.AvifPq -> null
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
     * - **SDR** lib (JXR/JXL): lib decode → plain SDR JPEG
     * - **HDR** lib: libultrahdr with **fixed MaxCLL 1000 nits** (MaxEdge JNI)
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
        val route = classifyPath(source, fileNameHint)
        val ok = when {
            route.needsUhdr -> {
                val lib = route as StillRoute.Lib
                writeConvertThumb(source, destJpeg, edge, fileNameHint, lib.codec)
            }
            route.isLibSdr -> writeLibSdrThumb(source, destJpeg, edge, quality, fileNameHint)
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
     * Archive / in-memory absolute HDR → Ultra HDR under [localRoot].
     * Used by [com.hippo.ehviewer.image.Image] for [com.hippo.ehviewer.image.ByteBufferSource].
     */
    suspend fun ensureUhdrFromBytes(bytes: ByteArray, fileNameHint: String): Path =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) error("empty HDR buffer: $fileNameHint")
            val route = classify(bytes, bytes.size, fileNameHint)
            if (!route.needsUhdr) error("not UHDR content: $fileNameHint route=$route")
            val lib = route as StillRoute.Lib
            ensureLocalRoot()
            val dest = localRoot / "${sha256HexBytes(bytes)}.jpg"
            val destFile = File(dest.toString())
            if (destFile.isFile && destFile.length() > 0L) return@withContext dest
            if (lib.codec == LibCodec.AvifPq) {
                when (probeAvifHdrKind(bytes)) {
                    1 -> error("gain-map AVIF should use platform path: $fileNameHint")
                }
            }
            if (!convertToUhdr(bytes, destFile, lib.codec, maxEdge = 0)) {
                if (lib.codec == LibCodec.AvifPq) {
                    // Soft path: caller may fall back to Coil; we still need a file — write raw.
                    Log.w(TAG, "AVIF PQ convert failed from bytes: $fileNameHint")
                    error("AVIF PQ convert failed: $fileNameHint")
                }
                error("${lib.codec} convert failed: $fileNameHint")
            }
            OriginDiskCache.scheduleTrim()
            dest
        }

    /**
     * Network B1: bytes already in RAM → classify → SDR keep original / HDR only `.jpg`.
     * Uses the single [convertSlots] so downloads need not hold convert CPU.
     */
    suspend fun finalizeNetworkBytes(
        bytes: ByteArray,
        primaryPath: Path,
        originalFileName: String,
    ): Path = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) error("empty download: $originalFileName")
        val route = classify(bytes, bytes.size, originalFileName)
        if (!route.needsUhdr) {
            writeBytesAtomic(bytes, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        val lib = route as StillRoute.Lib
        val outPath = uhdrSiblingOf(primaryPath)
        val outFile = File(outPath.toString())
        File(outFile.parent ?: error("no parent")).mkdirs()
        val ok = convertToUhdr(bytes, outFile, lib.codec, maxEdge = 0)
        if (ok) {
            val primary = File(primaryPath.toString())
            if (primary.absolutePath != outFile.absolutePath) primary.delete()
            return@withContext outPath
        }
        if (lib.codec == LibCodec.AvifPq) {
            writeBytesAtomic(bytes, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        error("HDR convert failed for $originalFileName")
    }

    /**
     * Commit a network page download from a disk temp (legacy / non-RAM path).
     * Prefer [finalizeNetworkBytes] for lib/avif candidates.
     */
    suspend fun finalizeNetworkDownload(
        tmp: File,
        primaryPath: Path,
        originalFileName: String,
    ): Path = withContext(Dispatchers.IO) {
        val route = classify(tmp, fileNameHint = originalFileName)
        if (!route.needsUhdr) {
            commitTmp(tmp, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        val bytes = runCatching { tmp.readBytes() }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            tmp.delete()
            return@withContext finalizeNetworkBytes(bytes, primaryPath, originalFileName)
        }
        val lib = route as StillRoute.Lib
        val outPath = uhdrSiblingOf(primaryPath)
        val outFile = File(outPath.toString())
        val ok = lib.codec == LibCodec.Jxr && convertJxrViaPath(tmp.absolutePath, outFile)
        if (ok) {
            tmp.delete()
            val primary = File(primaryPath.toString())
            if (primary.absolutePath != outFile.absolutePath) primary.delete()
            return@withContext outPath
        }
        if (lib.codec == LibCodec.AvifPq) {
            commitTmp(tmp, File(primaryPath.toString()))
            return@withContext primaryPath
        }
        tmp.delete()
        error("HDR convert failed for $originalFileName")
    }

    /**
     * Browse thumb from in-memory download — **MaxEdge only** for HDR (no full-page UHDR).
     * Does not write page-cache originals.
     */
    suspend fun writeThumbFromBytes(
        bytes: ByteArray,
        destJpeg: File,
        maxEdge: Int = OriginDiskCache.THUMB_EDGE,
        quality: Int = THUMB_JPEG_QUALITY,
        fileNameHint: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (destJpeg.isFile && destJpeg.length() > 0L) return@withContext true
        if (bytes.isEmpty()) return@withContext false
        val edge = maxEdge.coerceIn(64, 2048)
        val route = classify(bytes, bytes.size, fileNameHint)
        val ok = when {
            route.needsUhdr -> {
                val lib = route as StillRoute.Lib
                convertToUhdr(bytes, destJpeg, lib.codec, maxEdge = edge)
            }
            route.isLibSdr -> writeLibSdrThumbBytes(bytes, destJpeg, edge, quality, fileNameHint)
            else -> writePlatformThumbBytes(bytes, destJpeg, edge, quality)
        }
        if (ok && destJpeg.isFile && destJpeg.length() > 0L) {
            OriginDiskCache.scheduleTrim()
            true
        } else {
            false
        }
    }

    // ── private convert pipeline ──────────────────────────────────────────

    private suspend fun ensureUhdrLocal(source: Path, codec: LibCodec): Path {
        if (codec == LibCodec.AvifPq && isHeicImageExtension(source.name.substringAfterLast('.', ""))) {
            Log.i(TAG, "HEIC/HEIF → platform path (not libavif): ${source.name}")
            return source
        }
        val dest = localDerivedPath(source)
        ensureLocalRoot()
        val destFile = File(dest.toString())
        if (destFile.isFile && destFile.length() > 0L) return dest

        val bytes = runCatching {
            source.read { readByteArray() }
        }.onFailure {
            Log.e(TAG, "read failed: $source", it)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            if (codec == LibCodec.AvifPq) {
                Log.w(TAG, "AVIF unreadable, platform fallback: ${source.name}")
                return source
            }
            error("HDR source unreadable: ${source.name}")
        }

        if (codec == LibCodec.AvifPq) {
            when (probeAvifHdrKind(bytes)) {
                1 -> {
                    Log.i(TAG, "AVIF gain-map → platform path: ${source.name}")
                    return source
                }
            }
        }

        if (convertToUhdr(bytes, destFile, codec, maxEdge = 0)) return dest
        if (codec == LibCodec.AvifPq) {
            Log.w(TAG, "AVIF PQ convert failed, platform fallback: ${source.name}")
            return source
        }
        error("${codec.name} → Ultra HDR convert failed: ${source.name}")
    }

    /**
     * Single convert path: global convert slot + per-dest mutex + tmp + native + commit.
     * @param maxEdge 0 = full page (p99.99 MaxCLL); >0 = thumb fixed 1000 nits MaxEdge JNI
     */
    private suspend fun convertToUhdr(
        input: ByteArray,
        output: File,
        codec: LibCodec,
        maxEdge: Int = 0,
    ): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        if (input.isEmpty()) return@withContext false
        if (codec == LibCodec.AvifPq) {
            when (probeAvifHdrKind(input)) {
                1 -> return@withContext false // gain-map: keep original for platform
            }
        }
        convertSlots.withPermit {
            val lockKey = output.absolutePath
            val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
            mutex.withLock {
                if (output.isFile && output.length() > 0L) return@withLock true
                output.parentFile?.mkdirs()
                val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
                try {
                    val code = if (maxEdge > 0) {
                        when (codec) {
                            LibCodec.Jxr -> convertJxrBytesToUltraHdrMaxEdge(input, tmp.absolutePath, maxEdge)
                            LibCodec.Jxl -> convertJxlBytesToUltraHdrMaxEdge(input, tmp.absolutePath, maxEdge)
                            LibCodec.AvifPq -> convertAvifBytesToUltraHdrMaxEdge(input, tmp.absolutePath, maxEdge)
                        }
                    } else {
                        when (codec) {
                            LibCodec.Jxr -> convertJxrBytesToUltraHdr(input, tmp.absolutePath)
                            LibCodec.Jxl -> convertJxlBytesToUltraHdr(input, tmp.absolutePath)
                            LibCodec.AvifPq -> convertAvifBytesToUltraHdr(input, tmp.absolutePath)
                        }
                    }
                    if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                        Log.e(TAG, "convertToUhdr failed codec=$codec code=$code edge=$maxEdge in=${input.size}b")
                        tmp.delete()
                        return@withLock false
                    }
                    commitTmp(tmp, output)
                    if (maxEdge <= 0) OriginDiskCache.scheduleTrim()
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "convertToUhdr exception codec=$codec", e)
                    tmp.delete()
                    false
                }
            }
        }
    }

    private fun writeBytesAtomic(bytes: ByteArray, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File("${dest.absolutePath}.tmp.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            commitTmp(tmp, dest)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun writeLibSdrThumbBytes(
        bytes: ByteArray,
        destJpeg: File,
        maxEdge: Int,
        quality: Int,
        fileNameHint: String,
    ): Boolean {
        val bmp = decodeLibSdrBitmap(bytes, fileNameHint, maxEdge) ?: return false
        return try {
            destJpeg.parentFile?.mkdirs()
            val tmp = File("${destJpeg.absolutePath}.tmp.${System.nanoTime()}")
            FileOutputStream(tmp).use { out ->
                check(bmp.compress(Bitmap.CompressFormat.JPEG, quality, out))
            }
            commitTmp(tmp, destJpeg)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "writeLibSdrThumbBytes failed $fileNameHint", e)
            false
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun writePlatformThumbBytes(
        bytes: ByteArray,
        destJpeg: File,
        maxEdge: Int,
        quality: Int,
    ): Boolean = runCatching {
        destJpeg.parentFile?.mkdirs()
        val tmp = File("${destJpeg.absolutePath}.tmp.${System.nanoTime()}")
        val srcTmp = File("${destJpeg.absolutePath}.src.${System.nanoTime()}")
        try {
            FileOutputStream(srcTmp).use { it.write(bytes) }
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(srcTmp)) { decoder, info, _ ->
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
        } finally {
            srcTmp.delete()
            if (tmp.exists() && tmp.absolutePath != destJpeg.absolutePath) tmp.delete()
        }
    }.onFailure {
        Log.e(TAG, "writePlatformThumbBytes failed", it)
    }.getOrDefault(false)

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

    private suspend fun writeConvertThumb(
        source: Path,
        destJpeg: File,
        maxEdge: Int,
        fileNameHint: String,
        codec: LibCodec,
    ): Boolean {
        val bytes = runCatching { source.read { readByteArray() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Log.e(TAG, "writeConvertThumb: unreadable $fileNameHint")
            return false
        }
        return convertToUhdr(bytes, destJpeg, codec, maxEdge = maxEdge)
    }

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

    private fun writePlatformThumb(source: Path, destJpeg: File, maxEdge: Int, quality: Int): Boolean {
        return runCatching {
            val srcFile = File(source.toString())
            if (!srcFile.isFile || srcFile.length() <= 0L) {
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

    private fun sha256HexBytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(bytes)
        return dig.joinToString("") { b -> "%02x".format(b) }
    }
}
