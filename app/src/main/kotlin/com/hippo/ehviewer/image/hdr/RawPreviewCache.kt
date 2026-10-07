package com.hippo.ehviewer.image.hdr

import android.util.Log
import com.ehviewer.core.files.metadataOrNull
import com.hippo.ehviewer.jni.extractRawPreviewFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
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
    private const val TAG = "RawPreviewCache"
    private val gate = Mutex()

    suspend fun ensureJpeg(source: Path, demosaicFallback: Boolean): Path? = withContext(Dispatchers.IO) {
        val dest = File(appCtx.cacheDir, "raw_preview/${keyFor(source)}.jpg")
        gate.withLock {
            if (dest.isFile && dest.length() > 0L) return@withLock dest.toOkioPath()
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".tmp")
            val rc = try {
                source.withLocalRawFile { file ->
                    extractRawPreviewFile(file.absolutePath, tmp.absolutePath, demosaicFallback)
                }
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (_: UnsatisfiedLinkError) {
                -1
            } catch (e: Exception) {
                Log.e(TAG, "RAW preview unreadable: $source", e)
                -1
            }
            if (rc != 0 || !tmp.isFile || tmp.length() <= 0L) {
                Log.e(TAG, "RAW preview missing rc=$rc path=$source")
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

    private fun keyFor(source: Path): String {
        val text = source.toString()
        val (length, mtime) = if (isPhysicalRawPath(text)) {
            val file = File(text)
            file.length() to file.lastModified()
        } else {
            val meta = source.metadataOrNull()
            (meta?.size ?: -1L) to (meta?.lastModifiedAtMillis ?: -1L)
        }
        val raw = "$text|$length|$mtime"
        val dig = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
