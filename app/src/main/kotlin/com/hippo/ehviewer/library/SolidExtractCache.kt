package com.hippo.ehviewer.library

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Durable extract cache for solid network archives (RAR/CBR/7z "fake stream").
 *
 * Layout:
 * ```
 * {dataDir}/cache/solid_extract/{sha256(cacheKey)}/
 *   index.json
 *   pages/000000.jpg
 * ```
 *
 * Index is the lazy member list (no central directory). Seek bar max = listed members only.
 */
object SolidExtractCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/solid_extract").toOkioPath()
    }

    @Serializable
    data class Member(
        val i: Int,
        val name: String = "",
        val ext: String,
        val uncSize: Long = 0L,
    )

    @Serializable
    data class Index(
        val v: Int = 1,
        val cacheKey: String,
        val remoteSize: Long = 0L,
        val format: String = "unknown",
        val complete: Boolean = false,
        val members: List<Member> = emptyList(),
    )

    fun dirFor(cacheKey: String): Path = root / sha256Hex(cacheKey)

    fun indexPath(cacheKey: String): Path = dirFor(cacheKey) / "index.json"

    fun pagePath(cacheKey: String, index: Int, ext: String): Path {
        val safeExt = ext.lowercase().ifBlank { "bin" }.take(8)
        return dirFor(cacheKey) / "pages" / "%06d.%s".format(index, safeExt)
    }

    fun isPageCached(cacheKey: String, index: Int, ext: String): Boolean =
        isCachedFile(pagePath(cacheKey, index, ext))

    fun isCachedFile(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
    }

    fun loadIndex(cacheKey: String): Index? {
        val f = File(indexPath(cacheKey).toString())
        if (!f.isFile || f.length() == 0L) return null
        return runCatching {
            json.decodeFromString(Index.serializer(), f.readText())
        }.getOrNull()
    }

    fun saveIndex(index: Index) {
        val dest = File(indexPath(index.cacheKey).toString())
        dest.parentFile?.mkdirs()
        val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
        try {
            tmp.writeText(json.encodeToString(Index.serializer(), index))
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun allPagesPresent(cacheKey: String, index: Index): Boolean {
        if (index.members.isEmpty()) return false
        return index.members.all { m -> isPageCached(cacheKey, m.i, m.ext) }
    }

    fun isCompleteAndReady(cacheKey: String, remoteSize: Long = 0L): Index? {
        val idx = loadIndex(cacheKey) ?: return null
        if (!idx.complete) return null
        if (remoteSize > 0L && idx.remoteSize > 0L && idx.remoteSize != remoteSize) return null
        if (!allPagesPresent(cacheKey, idx)) return null
        return idx
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

    fun writePageFromFdCopy(cacheKey: String, index: Int, ext: String, srcFile: File): Path {
        val dest = pagePath(cacheKey, index, ext)
        File(dest.parent!!.toString()).mkdirs()
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
        try {
            srcFile.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(File(dest.toString()))) {
                tmp.copyTo(File(dest.toString()), overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return dest
    }

    fun extensionFor(cacheKey: String, index: Int): String? {
        loadIndex(cacheKey)?.members?.firstOrNull { it.i == index }?.ext?.let { return it }
        val pagesDir = File((dirFor(cacheKey) / "pages").toString())
        if (!pagesDir.isDirectory) return null
        val prefix = "%06d.".format(index)
        return pagesDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
            ?.name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
