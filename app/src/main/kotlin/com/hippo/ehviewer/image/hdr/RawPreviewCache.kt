package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.extractRawPreviewFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Embedded JPEG (or a 512 px demosaic) for a RAW still, so Coil never opens the RAW.
 * Keyed by path, length, and mtime. Cover and browse thumbs share the file.
 */
object RawPreviewCache {
    private val gate = Mutex()

    suspend fun ensureJpeg(source: Path, demosaicFallback: Boolean): Path? = withContext(Dispatchers.IO) {
        val file = File(source.toString())
        if (!file.isFile || file.length() <= 0L) return@withContext null
        val dest = File(appCtx.cacheDir, "raw_preview/${keyFor(file)}.jpg")
        gate.withLock {
            if (dest.isFile && dest.length() > 0L) return@withLock dest.toOkioPath()
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".tmp")
            val rc = try {
                extractRawPreviewFile(file.absolutePath, tmp.absolutePath, demosaicFallback)
            } catch (_: UnsatisfiedLinkError) {
                -1
            }
            if (rc != 0 || !tmp.isFile || tmp.length() <= 0L) {
                tmp.delete()
                return@withLock null
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (!dest.isFile || dest.length() <= 0L) null else dest.toOkioPath()
        }
    }

    private fun keyFor(file: File): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val dig = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
