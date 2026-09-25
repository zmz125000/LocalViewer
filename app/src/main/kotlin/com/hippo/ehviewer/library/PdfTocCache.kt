package com.hippo.ehviewer.library

import com.hippo.ehviewer.library.document.PdfTocEntry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * PDF reader bookmark cache.
 *
 * Not [DocumentExtractCache]. That index is the image reader's page list for the
 * same path (`cache/document_extract/{sha256(path)}/index.json`). This file lives
 * under `cache/pdf_toc/` and the hash is `pdf-toc:vN:key`, so the two never share
 * a directory.
 *
 * A hit is this file's size and page count. An empty chapter list is a hit (the
 * PDF has no outline). A stopped or failed scan is not written.
 */
internal object PdfTocCache {
    private const val FORMAT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Unit tests set this so they do not touch the app cache dir. */
    internal var rootOverride: File? = null

    private val root: File
        get() = rootOverride ?: File(appCtx.applicationInfo.dataDir, "cache/pdf_toc")

    private val locks = ConcurrentHashMap<String, Any>()

    @Serializable
    private data class FileBody(
        val v: Int = FORMAT_VERSION,
        val fileSize: Long,
        val pageCount: Int,
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    private data class Chapter(
        val title: String,
        val pageIndex: Int,
        val depth: Int,
    )

    /** Null on miss, including a size or page-count change. Empty is a real hit. */
    fun load(cacheKey: String, fileSize: Long, pageCount: Int): List<PdfTocEntry>? {
        if (cacheKey.isEmpty() || fileSize <= 0L || pageCount <= 0) return null
        val file = fileFor(cacheKey)
        if (!file.isFile || file.length() <= 0L) return null
        val body = runCatching {
            json.decodeFromString(FileBody.serializer(), file.readText())
        }.getOrNull() ?: return null
        if (body.v != FORMAT_VERSION || body.fileSize != fileSize || body.pageCount != pageCount) {
            return null
        }
        return body.chapters.map { PdfTocEntry(it.title, it.pageIndex, it.depth) }
    }

    fun save(cacheKey: String, fileSize: Long, pageCount: Int, chapters: List<PdfTocEntry>) {
        if (cacheKey.isEmpty() || fileSize <= 0L || pageCount <= 0) return
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            val dest = fileFor(cacheKey)
            dest.parentFile?.mkdirs()
            val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
            try {
                val body = FileBody(
                    fileSize = fileSize,
                    pageCount = pageCount,
                    chapters = chapters.map { Chapter(it.title, it.pageIndex, it.depth) },
                )
                tmp.writeText(json.encodeToString(FileBody.serializer(), body))
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists() && tmp.absolutePath != dest.absolutePath) tmp.delete()
            }
        }
    }

    internal fun fileFor(cacheKey: String): File = File(root, sha256Hex(identity(cacheKey)) + ".json")

    /** Hash input. Deliberately not the raw path the image index uses. */
    internal fun identity(cacheKey: String): String = "pdf-toc:v$FORMAT_VERSION:$cacheKey"

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
