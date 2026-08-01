package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.convertJxrToUltraHdr
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
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Converts absolute HDR / JPEG XR sources to Ultra HDR JPEG and stores results
 * under origin-cache roots.
 *
 * **Network:** prefer [uhdrSiblingOf] next to the download path; originals for
 * always-convert types are never kept. **Local:** non-destructive derived store
 * [localRoot] keyed by path + mtime + size.
 */
object HdrConvertCache {
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

    fun localDerivedPath(source: File): Path {
        val key = "local:${source.absolutePath}:${source.lastModified()}:${source.length()}"
        return localRoot / "${sha256Hex(key)}.$UHDR_CACHE_SUFFIX"
    }

    /**
     * Ensure [source] is available as Ultra HDR for the reader.
     * @return path to open (converted or original when no convert needed)
     */
    suspend fun ensureReadable(source: File, fileNameHint: String = source.name): Path =
        withContext(Dispatchers.IO) {
            val sniff = sniffHdr(source, fileNameHint = fileNameHint)
            if (!sniff.needsConvert) {
                return@withContext source.toOkioPath()
            }
            when (sniff.kind) {
                HdrKind.JpegXr -> ensureJxrConverted(source)
                HdrKind.AbsolutePqHlg -> {
                    // P2: PQ path not fully wired yet — fall through to original (may SDR).
                    // Local derived convert will land with platform HDR decode + libultrahdr.
                    source.toOkioPath()
                }
                else -> source.toOkioPath()
            }
        }

    /**
     * Convert JPEG XR at [input] → Ultra HDR at [output] (atomic).
     * @return true if [output] is ready
     */
    suspend fun convertJxrFile(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        if (output.isFile && output.length() > 0L) return@withContext true
        val lockKey = output.absolutePath
        val mutex = pathLocks.getOrPut(lockKey) { Mutex() }
        mutex.withLock {
            if (output.isFile && output.length() > 0L) return@withLock true
            output.parentFile?.mkdirs()
            val tmp = File("${output.absolutePath}.tmp.${System.nanoTime()}")
            try {
                val code = convertJxrToUltraHdr(input.absolutePath, tmp.absolutePath)
                if (code != 0 || !tmp.isFile || tmp.length() <= 0L) {
                    tmp.delete()
                    return@withLock false
                }
                commitTmp(tmp, output)
                OriginDiskCache.scheduleTrim()
                true
            } catch (e: Throwable) {
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
            HdrKind.AbsolutePqHlg -> false // P2
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
        // Convert failed: keep original if it is the primary (non-jxr), else error path
        primaryPath
    }

    private suspend fun ensureJxrConverted(source: File): Path {
        val dest = localDerivedPath(source)
        ensureLocalRoot()
        val destFile = File(dest.toString())
        if (convertJxrFile(source, destFile)) return dest
        error("JPEG XR → Ultra HDR convert failed: ${source.name}")
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
