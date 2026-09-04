package com.hippo.ehviewer.library

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ehviewer.core.i18n.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

/** Thrown when a ZIP member is over [ZipMemberCover.MAX_CACHE_BYTES] and was not written to NAND. */
class ZipMemberTooLargeException(val sizeBytes: Long) : IOException("ZIP member ${sizeBytes / (1024L * 1024L)} MB over 100 MB cache limit")

fun Throwable.isZipMemberTooLarge(): Boolean = this is ZipMemberTooLargeException || generateSequence(cause) { it.cause }.any { it is ZipMemberTooLargeException }

/**
 * Extract one ZIP/CBZ image or video member to `cache/zip_folder_pages` for
 * covers / [ZipFolderPageLoader]. Other member types are refused so browse
 * cannot dump PDFs or nested archives into cache without an explicit open.
 */
object ZipMemberCover {
    /** Cap NAND writes for extracted zip members (open-in-zip / covers / pages). */
    const val MAX_CACHE_BYTES = 100L * 1024L * 1024L

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun cacheDir(): File = File(appCtx.applicationInfo.dataDir, "cache/zip_folder_pages").also { it.mkdirs() }

    fun destFile(zipKey: String, memberRel: String): File {
        val member = memberRel.replace('\\', '/').trimStart('/')
        val nameKey = sha256(member).take(20)
        val ext = member.substringAfterLast('.', missingDelimiterValue = "bin").lowercase().ifEmpty { "bin" }
        val zip = sha256(zipKey).take(16)
        return File(cacheDir(), "${zip}_$nameKey.$ext")
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
            if (dest.isFile && dest.length() > 0L) dest.absolutePath.toPath() else null
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

    fun ensureLocal(
        zipPath: String,
        memberRel: String,
        notifyTooLarge: Boolean = true,
    ): Path? = ensure(zipPath, memberRel, notifyTooLarge) {
        openLocalArchiveByteSource(zipPath.toPath())
    }
}
