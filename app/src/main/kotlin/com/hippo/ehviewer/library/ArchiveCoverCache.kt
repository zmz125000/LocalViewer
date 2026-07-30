package com.hippo.ehviewer.library

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.openSolidSequential
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.jni.solidCurrentExtension
import com.hippo.ehviewer.jni.solidExtractCurrentToFd
import com.hippo.ehviewer.jni.solidNextPlayable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * First-page JPEG thumbs for archive galleries (library + folder / network browse).
 * Long edge [THUMB_EDGE] matches SMB/WebDAV browse thumbs (768).
 *
 * - Local non-solid: mmap extract page 0
 * - Network ZIP/TAR: [ensureStreamCover] (range + coverOnly)
 * - Network RAR/7z: [ensureSolidStreamCover] (sequential first playable only)
 * - After solid reader: [writeCoverFromExtractedPage] from extract cache page 0
 */
object ArchiveCoverCache {
    /** Align with [com.hippo.ehviewer.smb.SmbCache.THUMB_DISK_EDGE]. */
    const val THUMB_EDGE = 768

    private const val THUMB_JPEG_QUALITY = 85
    private const val FORMAT_VERSION = 1

    private val extractSlots = Semaphore(1)

    /** Paths known present on disk — main-thread [isCached] must not touch the filesystem. */
    private val knownPresent = ConcurrentHashMap.newKeySet<String>()

    private val thumbRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/archive_thumb").toOkioPath()
    }

    fun thumbPathFor(archivePath: String, mtimeMs: Long = 0L, sizeBytes: Long = 0L): Path {
        val key = "archthumb:v$FORMAT_VERSION:$archivePath:$mtimeMs:$sizeBytes@$THUMB_EDGE"
        return thumbRoot / "${sha256Hex(key)}.jpg"
    }

    /**
     * Fast cache presence check (matches [com.hippo.ehviewer.smb.SmbCache.isCached]).
     * - **Main**: memory only — no [File] I/O / StrictMode.
     * - **Background**: probes disk and updates [knownPresent].
     */
    fun isCached(path: Path): Boolean {
        if (Looper.getMainLooper().isCurrentThread) {
            return knownPresent.contains(path.toString())
        }
        return isCachedOnDisk(path)
    }

    /** Authoritative disk probe — call from IO or accept StrictMode if on main. */
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

    /**
     * Ensure a small cover JPEG exists for [archivePath].
     * @return thumb path, or null if skipped (solid/password/busy/error).
     */
    suspend fun ensureCover(archivePath: Path): Path? = withIOContext {
        val name = archivePath.name
        if (!prefersArchiveCoverExtract(name)) return@withIOContext null
        val file = File(archivePath.toString())
        if (!file.isFile || file.length() == 0L) {
            // SAF paths may not be plain File — still try openFileDescriptor below.
        }
        val mtime = file.takeIf { it.isFile }?.lastModified() ?: 0L
        val size = file.takeIf { it.isFile }?.length() ?: 0L
        val dest = thumbPathFor(archivePath.toString(), mtime, size)
        if (isCachedOnDisk(dest)) return@withIOContext dest

        extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withIOContext dest
            ArchiveAccess.tryWithArchive {
                extractCoverLocked(archivePath, dest)
            } ?: return@withIOContext null
        }
        dest.takeIf { isCachedOnDisk(it) }
    }

    /**
     * Write cover from an **already open** archive (reader holds [ArchiveAccess]).
     * Extracts page 0 without reopening.
     * [archiveKey] may be a local path or a remote stream key (`smb:id:path`).
     */
    fun writeCoverFromOpenArchive(archiveKey: String, destHintMtime: Long = 0L, destHintSize: Long = 0L): Path? {
        val base = archiveKey.substringAfterLast('/').substringAfterLast(':')
        if (base.isNotEmpty() && isSolidArchiveFileName(base)) return null
        val dest = thumbPathFor(archiveKey, destHintMtime, destHintSize)
        if (isCachedOnDisk(dest)) return dest
        return runCatching {
            extractPage0ToJpeg(dest)
            dest.takeIf { isCachedOnDisk(it) }
        }.onFailure { logcat(it) }.getOrNull()
    }

    /**
     * Cover from an already-extracted page file (solid fake-stream page 0).
     * Allows solid remote keys that [writeCoverFromOpenArchive] would skip.
     */
    fun writeCoverFromExtractedPage(archiveKey: String, pageFile: Path): Path? {
        val dest = thumbPathFor(archiveKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return dest
        return runCatching {
            val src = File(pageFile.toString())
            if (!src.isFile || src.length() == 0L) return null
            File(dest.parent!!.toString()).mkdirs()
            val jpgTmp = File("${dest}.jpg.${System.nanoTime()}")
            try {
                writeSubsampledJpeg(src, jpgTmp, THUMB_EDGE, THUMB_JPEG_QUALITY)
                val destFile = File(dest.toString())
                if (!jpgTmp.renameTo(destFile)) {
                    jpgTmp.copyTo(destFile, overwrite = true)
                    jpgTmp.delete()
                }
                if (destFile.isFile && destFile.length() > 0L) {
                    markPresent(dest)
                    dest
                } else {
                    null
                }
            } finally {
                if (jpgTmp.exists()) jpgTmp.delete()
            }
        }.onFailure { logcat(it) }.getOrNull()
    }

    /**
     * Stream-open a remote ZIP/TAR archive, extract page 0 cover, close. No full-archive download.
     *
     * [openSource] is invoked **only after** the extract slot is held so SMB/WebDAV
     * connections are not opened for every grid cell waiting in the queue.
     * Cache key uses size=0 so hits work without a network size probe.
     *
     * Solid formats: use [ensureSolidStreamCover] (this method returns null for solid names).
     *
     * @return thumb path or null if busy/password/error/solid.
     */
    suspend fun ensureStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
    ): Path? = withIOContext {
        val base = cacheKey.substringAfterLast('/').substringAfterLast(':')
        if (base.isNotEmpty() && isSolidArchiveFileName(base)) return@withIOContext null
        // Stable path without remote size (avoids opening the archive just to hash the key).
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return@withIOContext dest
        extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withIOContext dest
            ArchiveAccess.tryWithArchive {
                openSource().use { source ->
                    val bridge = ArchiveStreamBridge(source)
                    try {
                        // coverOnly: ZIP natural-first only / TAR stop at first image (EOCD/headers).
                        // Always pass coverOnly explicitly (no default on external JNI).
                        val n = com.hippo.ehviewer.jni.openArchiveStream(
                            bridge,
                            source.size,
                            /* sortEntries = */ false,
                            /* coverOnly = */ true,
                        )
                        if (n <= 0 || com.hippo.ehviewer.jni.needPassword()) return@tryWithArchive null
                        writeCoverFromOpenArchive(cacheKey, 0L, 0L)
                    } finally {
                        com.hippo.ehviewer.jni.closeArchive()
                        bridge.close()
                    }
                }
            }
        }
    }

    /**
     * Lazy browse thumb for network solid archives (RAR/CBR/7z):
     * 1. Hit existing JPEG thumb
     * 2. Hit solid extract page 0 already on disk (after a prior reader session)
     * 3. Sequential open → first playable member only → subsample JPEG → close
     *
     * Does **not** full-download the archive. Skips when [ArchiveAccess] is busy (reader open);
     * grid [ON_RESUME] retries. Passworded solids are skipped.
     */
    suspend fun ensureSolidStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
    ): Path? = withIOContext {
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return@withIOContext dest

        // Prefer page already extracted by a prior solid reader session.
        coverFromSolidExtractCache(cacheKey)?.let { return@withIOContext it }

        extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withIOContext dest
            coverFromSolidExtractCache(cacheKey)?.let { return@withIOContext it }
            val locked = ArchiveAccess.tryWithArchive {
                openSource().use { source ->
                    val bridge = ArchiveStreamBridge(source)
                    try {
                        val opened = openSolidSequential(bridge, source.size)
                        if (opened == 0) {
                            logcat("SolidCover") { "openSolidSequential failed key=$cacheKey" }
                            return@tryWithArchive null
                        }
                        // Password only known after headers; don't check needPassword() pre-walk.
                        val idx = solidNextPlayable()
                        if (idx < 0) {
                            logcat("SolidCover") {
                                "no playable member idx=$idx needPw=${needPassword()} key=$cacheKey"
                            }
                            return@tryWithArchive null
                        }
                        if (needPassword()) {
                            logcat("SolidCover") { "passworded solid skipped key=$cacheKey" }
                            return@tryWithArchive null
                        }
                        val ext = solidCurrentExtension().ifBlank { "bin" }.take(8)
                        val tmp = File(
                            appCtx.cacheDir,
                            "solid_cover_${System.nanoTime()}.$ext",
                        )
                        try {
                            ParcelFileDescriptor.open(
                                tmp,
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_TRUNCATE,
                            ).use { pfd ->
                                if (!solidExtractCurrentToFd(pfd.fd)) {
                                    logcat("SolidCover") { "extract page0 failed key=$cacheKey" }
                                    return@tryWithArchive null
                                }
                            }
                            if (!tmp.isFile || tmp.length() == 0L) return@tryWithArchive null
                            // Also seed solid extract page 0 so reader cold-open can reuse.
                            runCatching {
                                SolidExtractCache.writePageFromFdCopy(cacheKey, 0, ext, tmp)
                            }
                            writeCoverFromExtractedPage(cacheKey, tmp.toOkioPath())
                        } finally {
                            tmp.delete()
                        }
                    } catch (e: Throwable) {
                        logcat("SolidCover", e)
                        null
                    } finally {
                        closeArchive()
                        bridge.close()
                    }
                }
            }
            if (locked == null) {
                logcat("SolidCover") { "archive busy or failed key=$cacheKey" }
            }
            locked
        }
    }

    /**
     * Disk-only cover resolve (no network): existing JPEG thumb, or solid extract page 0.
     * Used by browse rows when "download remote thumbs" is off or before opening solid.
     */
    fun tryDiskCover(cacheKey: String): Path? {
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return dest
        return coverFromSolidExtractCache(cacheKey)
    }

    /** Cover from solid_extract pages/000000.* if present. */
    private fun coverFromSolidExtractCache(cacheKey: String): Path? {
        val ext = SolidExtractCache.extensionFor(cacheKey, 0) ?: return null
        val page = SolidExtractCache.pagePath(cacheKey, 0, ext)
        if (!SolidExtractCache.isCachedFile(page)) return null
        return writeCoverFromExtractedPage(cacheKey, page)
    }

    private fun extractCoverLocked(archivePath: Path, dest: Path): Path? {
        val pfd = try {
            archivePath.openFileDescriptor("r")
        } catch (e: Throwable) {
            logcat(e)
            return null
        }
        pfd.use { fd ->
            val count = openArchive(fd.fd, fd.statSize, true)
            try {
                if (count <= 0) return null
                if (needPassword()) return null
                extractPage0ToJpeg(dest)
                return dest.takeIf { isCachedOnDisk(it) }
            } finally {
                closeArchive()
            }
        }
    }

    private fun extractPage0ToJpeg(dest: Path) {
        val buffer = extractToByteBuffer(0) ?: error("extract page 0 failed")
        try {
            check(buffer.isDirect)
            File(dest.parent!!.toString()).mkdirs()
            val rawTmp = File("${dest}.raw.${System.nanoTime()}")
            val jpgTmp = File("${dest}.jpg.${System.nanoTime()}")
            try {
                writeBufferToFile(buffer, rawTmp)
                writeSubsampledJpeg(rawTmp, jpgTmp, THUMB_EDGE, THUMB_JPEG_QUALITY)
                val destFile = File(dest.toString())
                if (!jpgTmp.renameTo(destFile)) {
                    jpgTmp.copyTo(destFile, overwrite = true)
                    jpgTmp.delete()
                }
                if (destFile.isFile && destFile.length() > 0L) {
                    markPresent(dest)
                }
            } finally {
                rawTmp.delete()
                if (jpgTmp.exists()) jpgTmp.delete()
            }
        } finally {
            releaseByteBuffer(buffer)
        }
    }

    private fun writeBufferToFile(buffer: ByteBuffer, file: File) {
        val dup = buffer.duplicate()
        dup.clear()
        FileOutputStream(file).channel.use { ch ->
            while (dup.hasRemaining()) {
                ch.write(dup)
            }
        }
    }

    private fun writeSubsampledJpeg(source: File, destJpeg: File, maxEdge: Int, quality: Int) {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
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
            FileOutputStream(destJpeg).use { out ->
                check(decoded.compress(Bitmap.CompressFormat.JPEG, quality, out))
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
