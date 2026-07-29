package com.hippo.ehviewer.library

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.releaseByteBuffer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * First-page JPEG thumbs for **local** archive galleries (library + folder browse).
 * Long edge [THUMB_EDGE] matches SMB/WebDAV browse thumbs (768).
 * Skips solid formats ([isSolidArchiveFileName] / 7z).
 */
object ArchiveCoverCache {
    /** Align with [com.hippo.ehviewer.smb.SmbCache.THUMB_DISK_EDGE]. */
    const val THUMB_EDGE = 768

    private const val THUMB_JPEG_QUALITY = 85
    private const val FORMAT_VERSION = 1

    private val extractSlots = Semaphore(1)

    private val thumbRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/archive_thumb").toOkioPath()
    }

    fun thumbPathFor(archivePath: String, mtimeMs: Long = 0L, sizeBytes: Long = 0L): Path {
        val key = "archthumb:v$FORMAT_VERSION:$archivePath:$mtimeMs:$sizeBytes@$THUMB_EDGE"
        return thumbRoot / "${sha256Hex(key)}.jpg"
    }

    fun isCached(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
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
        if (isCached(dest)) return@withIOContext dest

        extractSlots.withPermit {
            if (isCached(dest)) return@withIOContext dest
            ArchiveAccess.tryWithArchive {
                extractCoverLocked(archivePath, dest)
            } ?: return@withIOContext null
        }
        dest.takeIf { isCached(it) }
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
        if (isCached(dest)) return dest
        return runCatching {
            extractPage0ToJpeg(dest)
            dest.takeIf { isCached(it) }
        }.onFailure { logcat(it) }.getOrNull()
    }

    /**
     * Stream-open [source], extract page 0 cover, close. No full-archive download.
     * @return thumb path or null if busy/password/error/solid.
     */
    suspend fun ensureStreamCover(source: ArchiveByteSource, cacheKey: String): Path? = withIOContext {
        val base = cacheKey.substringAfterLast('/').substringAfterLast(':')
        if (base.isNotEmpty() && isSolidArchiveFileName(base)) return@withIOContext null
        val dest = thumbPathFor(cacheKey, 0L, source.size)
        if (isCached(dest)) return@withIOContext dest
        extractSlots.withPermit {
            if (isCached(dest)) return@withIOContext dest
            ArchiveAccess.tryWithArchive {
                val bridge = ArchiveStreamBridge(source)
                try {
                    val n = com.hippo.ehviewer.jni.openArchiveStream(bridge, source.size, true)
                    if (n <= 0 || com.hippo.ehviewer.jni.needPassword()) return@tryWithArchive null
                    writeCoverFromOpenArchive(cacheKey, 0L, source.size)
                } finally {
                    com.hippo.ehviewer.jni.closeArchive()
                    bridge.close()
                }
            }
        }
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
                return dest.takeIf { isCached(it) }
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
                if (!jpgTmp.renameTo(File(dest.toString()))) {
                    jpgTmp.copyTo(File(dest.toString()), overwrite = true)
                    jpgTmp.delete()
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
