package com.hippo.ehviewer.image.hdr

import android.util.Log
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.read
import com.hippo.ehviewer.jni.convertAvifBytesToUltraHdr
import com.hippo.ehviewer.jni.convertJxrBytesToUltraHdr
import com.hippo.ehviewer.jni.convertJxrToUltraHdr
import com.hippo.ehviewer.jni.probeAvifHdrKind
import com.hippo.ehviewer.library.OriginDiskCache
import java.io.File
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
 * Converts absolute HDR / JPEG XR sources to Ultra HDR JPEG and stores results
 * under origin-cache roots.
 *
 * **Network:** prefer [uhdrSiblingOf] next to the download path; originals for
 * always-convert types are never kept. **Local:** non-destructive derived store
 * [localRoot] keyed by path identity + mtime + size (works for SAF content:// too).
 *
 * Local JXR/AVIF: Okio read → memory decode (jxrlib / libavif) → Ultra HDR JPEG only.
 */
object HdrConvertCache {
    private const val TAG = "HdrConvert"

    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    /** Derived Ultra HDR for local files (user originals untouched). */
    private val localRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/hdr_ultrahdr").toOkioPath()
    }

    fun ensureLocalRoot() {
        File(localRoot.toString()).mkdirs()
    }

    /**
     * Sibling Ultra HDR path for a network/extract page cache file.
     * `…/deadbeef.avif` → `…/deadbeef.uhdr.jpg`
     * `…/deadbeef.jxr` → `…/deadbeef.uhdr.jpg`
     */
    fun uhdrSiblingOf(cachePath: Path): Path {
        val name = cachePath.name
        val hash = name.substringBefore('.')
        return cachePath.parent!! / "$hash.$UHDR_CACHE_SUFFIX"
    }

    /**
     * Page path for network caches: always-convert extensions resolve directly to Ultra HDR.
     * Other types keep original extension; after convert, [resolvePagePath] prefers Ultra HDR.
     */
    fun networkStorageName(hash: String, originalExt: String): String {
        val ext = originalExt.lowercase().removePrefix(".")
        return if (isHdrAlwaysConvertExtension(ext)) {
            "$hash.$UHDR_CACHE_SUFFIX"
        } else {
            "$hash.$ext"
        }
    }

    /**
     * Prefer converted Ultra HDR when present; else original [primary].
     */
    fun resolvePagePath(primary: Path): Path {
        val uhdr = uhdrSiblingOf(primary)
        if (isPresent(uhdr)) return uhdr
        // primary may already be *.uhdr.jpg
        if (primary.name.endsWith(".$UHDR_CACHE_SUFFIX") && isPresent(primary)) return primary
        return primary
    }

    fun isPresent(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
    }

    /** True when [path] is a normal `/…` filesystem path (not SAF content://). */
    fun isPhysicalPath(path: Path): Boolean = path.toString().startsWith('/')

    fun localDerivedPath(source: Path): Path {
        val meta = source.metadataOrNull()
        val mtime = meta?.lastModifiedAtMillis ?: 0L
        val size = meta?.size ?: 0L
        // Include encode version so content-boost encode changes re-convert.
        val key = "local:v$UHDR_ENCODE_VERSION:${source}:${mtime}:$size"
        return localRoot / "${sha256Hex(key)}.$UHDR_CACHE_SUFFIX"
    }

    /**
     * Ensure [source] is available as Ultra HDR for the reader.
     * Supports physical files and SAF/content Okio paths.
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
                else -> source
            }
        }

    /** @deprecated Prefer [ensureReadable] with Okio [Path]. */
    suspend fun ensureReadable(source: File, fileNameHint: String = source.name): Path =
        ensureReadable(source.toOkioPath(), fileNameHint)

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
     * After a network download of raw bytes to [downloaded], maybe convert and
     * return the path the reader should use. Deletes original when convert succeeds
     * and [deleteOriginalOnConvert] is true (network policy).
     */
    suspend fun finalizeNetworkDownload(
        downloaded: File,
        primaryPath: Path,
        originalFileName: String,
        deleteOriginalOnConvert: Boolean = true,
    ): Path = withContext(Dispatchers.IO) {
        val sniff = sniffHdr(downloaded, fileNameHint = originalFileName)
        if (!sniff.needsConvert) {
            return@withContext primaryPath
        }
        val outPath = when {
            primaryPath.name.endsWith(".$UHDR_CACHE_SUFFIX") -> primaryPath
            else -> uhdrSiblingOf(primaryPath)
        }
        val outFile = File(outPath.toString())
        val ok = when (sniff.kind) {
            HdrKind.JpegXr -> convertJxrFile(downloaded, outFile)
            HdrKind.AbsolutePqHlg -> convertAvifFile(downloaded, outFile)
            else -> false
        }
        if (ok) {
            if (deleteOriginalOnConvert) {
                val primaryFile = File(primaryPath.toString())
                if (primaryFile.absolutePath != outFile.absolutePath) {
                    primaryFile.delete()
                }
                if (downloaded.absolutePath != outFile.absolutePath &&
                    downloaded.absolutePath != primaryFile.absolutePath
                ) {
                    downloaded.delete()
                }
            }
            return@withContext outPath
        }
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
        error("AVIF PQ/HLG → Ultra HDR convert failed: ${source.name}")
    }

    suspend fun convertAvifFile(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
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
