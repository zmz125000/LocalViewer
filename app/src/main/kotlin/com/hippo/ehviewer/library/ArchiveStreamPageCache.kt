package com.hippo.ehviewer.library

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Caches **extracted page images** from stream-opened archives (not the archive file).
 * Keyed by remote identity + page index.
 */
object ArchiveStreamPageCache {
    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/archive_pages").toOkioPath()
    }

    fun pagePath(cacheKey: String, index: Int, ext: String): Path {
        val dir = root / sha256Hex(cacheKey)
        val safeExt = ext.lowercase().ifBlank { "bin" }.take(8)
        return dir / "$index.$safeExt"
    }

    fun isCached(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
    }

    fun writePage(cacheKey: String, index: Int, ext: String, buffer: ByteBuffer): Path {
        val dest = pagePath(cacheKey, index, ext)
        File(dest.parent!!.toString()).mkdirs()
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
        try {
            val dup = buffer.duplicate()
            dup.clear()
            FileOutputStream(tmp).channel.use { ch ->
                while (dup.hasRemaining()) ch.write(dup)
            }
            if (!tmp.renameTo(File(dest.toString()))) {
                tmp.copyTo(File(dest.toString()), overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return dest
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
