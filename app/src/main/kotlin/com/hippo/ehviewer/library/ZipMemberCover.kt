package com.hippo.ehviewer.library

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import splitties.init.appCtx

/** Thrown when a ZIP member is over [ZipMemberCover.MAX_CACHE_BYTES] and was not written to NAND. */
class ZipMemberTooLargeException(val sizeBytes: Long) : IOException("ZIP member ${sizeBytes / (1024L * 1024L)} MB over 100 MB cache limit")

fun Throwable.isZipMemberTooLarge(): Boolean = this is ZipMemberTooLargeException || generateSequence(cause) { it.cause }.any { it is ZipMemberTooLargeException }

/**
 * Extract one ZIP/CBZ image or video member.
 *
 * - **Reader pages** (`cache/zip_folder_pages`): [ensure] / [ZipFolderPageLoader] when
 *   [com.hippo.ehviewer.Settings.disableReaderNetworkCache] is off, or
 *   [com.hippo.ehviewer.Settings.saveThumbOriginalCache] on a zip-as-dir thumb.
 * - **Browse thumbs**: [ensureBrowseThumb] writes a small WebP only (same MaxEdge path
 *   as folder image thumbs). Range-read via [ZipCentralDirectory.extract].
 *
 * Other member types are refused so browse cannot dump PDFs or nested archives
 * into cache without an explicit open.
 */
object ZipMemberCover {
    /** Cap NAND writes for extracted zip members (open-in-zip / covers / pages). */
    const val MAX_CACHE_BYTES = 100L * 1024L * 1024L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val thumbLocks = ConcurrentHashMap<String, Mutex>()

    private fun cacheDir(): File = File(appCtx.applicationInfo.dataDir, "cache/zip_folder_pages").also { it.mkdirs() }

    fun destFile(zipKey: String, memberRel: String): File {
        val member = memberRel.replace('\\', '/').trimStart('/')
        val nameKey = sha256(member).take(20)
        val ext = member.substringAfterLast('.', missingDelimiterValue = "bin").lowercase().ifEmpty { "bin" }
        val zip = sha256(zipKey).take(16)
        return File(cacheDir(), "${zip}_$nameKey.$ext")
    }

    /**
     * Synthetic remote used as the SMB/WebDAV thumb-cache key (`zipRel!memberRel`).
     * `!` is not a share path separator, so it cannot collide with a real file.
     */
    fun thumbRemote(zipRel: String, memberRel: String): String {
        val zip = zipRel.replace('\\', '/').trimStart('/')
        val member = memberRel.replace('\\', '/').trimStart('/')
        return "$zip!$member"
    }

    fun sha256(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    fun notifyTooLarge(sizeBytes: Long) {
        val mb = ((sizeBytes + 1024L * 1024L - 1) / (1024L * 1024L)).toInt().coerceAtLeast(101)
        val text = appCtx.getString(R.string.zip_member_cache_too_large, mb)
        mainHandler.post {
            Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show()
        }
    }

    fun rejectIfTooLarge(entry: ZipCentralDirectory.Entry, notify: Boolean): Boolean {
        if (entry.uncompressedSize <= MAX_CACHE_BYTES) return false
        if (notify) {
            notifyTooLarge(entry.uncompressedSize)
            throw ZipMemberTooLargeException(entry.uncompressedSize)
        }
        return true
    }

    fun ensure(
        zipKey: String,
        memberRel: String,
        notifyTooLarge: Boolean = true,
        openSource: () -> ArchiveByteSource?,
    ): Path? {
        if (!isZipMemberCoverExtractAllowed(memberRel)) return null
        val dest = destFile(zipKey, memberRel)
        if (dest.isFile && dest.length() > 0L) return dest.absolutePath.toPath()
        val bytes = extractBytes(zipKey, memberRel, notifyTooLarge, openSource) ?: return null
        dest.parentFile?.mkdirs()
        val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (dest.isFile && dest.length() > 0L) {
                OriginDiskCache.scheduleTrim()
                dest.absolutePath.toPath()
            } else {
                null
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Same as [ensure] but keeps the member in RAM — no `zip_folder_pages` write. */
    fun extractBytes(
        zipKey: String,
        memberRel: String,
        notifyTooLarge: Boolean = true,
        openSource: () -> ArchiveByteSource?,
    ): ByteArray? {
        if (!isZipMemberCoverExtractAllowed(memberRel)) return null
        val dest = destFile(zipKey, memberRel)
        if (dest.isFile && dest.length() > 0L) return dest.readBytes()
        val source = openSource() ?: return null
        return try {
            val cd = ZipCentralDirectory.open(source) ?: return null
            val entry = cd.find(memberRel) ?: return null
            if (rejectIfTooLarge(entry, notifyTooLarge)) return null
            cd.extract(entry, maxBytes = MAX_CACHE_BYTES)
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * Zip-as-dir browse thumb: small WebP at [dest] (MaxEdge, same as folder image thumbs).
     *
     * 1. Thumb hit (WebP or leftover JPEG) → return
     * 2. Reader original already in [destFile] → subsample, no network
     * 3. [cacheOriginal] → [ensure] then subsample (save-thumb-original setting)
     * 4. Else range-extract to RAM → [HdrConvertCache.writeThumbFromBytes]
     */
    suspend fun ensureBrowseThumb(
        zipKey: String,
        memberRel: String,
        destJpeg: File,
        cacheOriginal: Boolean,
        notifyTooLarge: Boolean = false,
        openSource: () -> ArchiveByteSource?,
    ): Path? = withContext(Dispatchers.IO) {
        if (!isImageFileName(memberRel)) return@withContext null
        OriginDiskCache.existingThumb(destJpeg)?.let { return@withContext it.absolutePath.toPath() }
        val mutex = thumbLocks.getOrPut(destJpeg.path) { Mutex() }
        mutex.withLock {
            OriginDiskCache.existingThumb(destJpeg)?.let { return@withLock it.absolutePath.toPath() }
            destJpeg.parentFile?.mkdirs()
            val origin = destFile(zipKey, memberRel)
            if (origin.isFile && origin.length() > 0L) {
                return@withLock encodeThumbFromFile(origin, destJpeg, memberRel)
            }
            if (cacheOriginal) {
                val written = ensure(zipKey, memberRel, notifyTooLarge, openSource) ?: return@withLock null
                return@withLock encodeThumbFromFile(File(written.toString()), destJpeg, memberRel)
            }
            val bytes = extractBytes(zipKey, memberRel, notifyTooLarge, openSource) ?: return@withLock null
            val ok = HdrConvertCache.writeThumbFromBytes(
                bytes = bytes,
                destJpeg = destJpeg,
                fileNameHint = memberRel.substringAfterLast('/').substringAfterLast('\\'),
            )
            if (ok && destJpeg.isFile && destJpeg.length() > 0L) destJpeg.absolutePath.toPath() else null
        }
    }

    fun ensureLocal(
        zipPath: String,
        memberRel: String,
        notifyTooLarge: Boolean = true,
    ): Path? = ensure(zipPath, memberRel, notifyTooLarge) {
        openLocalArchiveByteSource(zipPath.toPath())
    }

    private suspend fun encodeThumbFromFile(origin: File, dest: File, memberRel: String): Path? {
        val ok = HdrConvertCache.writeThumb(
            source = origin.toOkioPath(),
            dest = dest,
            fileNameHint = memberRel.substringAfterLast('/').substringAfterLast('\\'),
        )
        return if (ok && dest.isFile && dest.length() > 0L) dest.absolutePath.toPath() else null
    }
}
