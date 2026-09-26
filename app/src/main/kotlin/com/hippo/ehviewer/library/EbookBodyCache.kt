package com.hippo.ehviewer.library

import com.hippo.ehviewer.library.document.EbookChapter
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Parsed ebook chapter bodies for the PDF-reader ebook path.
 *
 * Not [PdfTocCache] (`cache/pdf_toc/`) and not [DocumentExtractCache]
 * (`cache/document_extract/`). This file lives under `cache/ebook_body/` and the
 * hash is `ebook-body:vN:key`.
 *
 * A hit is this file's size. An empty chapter list is not stored (parse failed).
 * A stopped scan is not written.
 */
internal object EbookBodyCache {
    private const val FORMAT_VERSION = 3

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Unit tests set this so they do not touch the app cache dir. */
    internal var rootOverride: File? = null

    private val root: File
        get() = rootOverride ?: File(appCtx.applicationInfo.dataDir, "cache/ebook_body")

    private val locks = ConcurrentHashMap<String, Any>()

    @Serializable
    private data class FileBody(
        val v: Int = FORMAT_VERSION,
        val fileSize: Long,
        val charset: String = "auto",
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    private data class Chapter(
        val title: String,
        val text: String,
        val depth: Int,
    )

    /** Null on miss, including a size or charset change. */
    fun load(cacheKey: String, fileSize: Long, charset: String = "auto"): List<EbookChapter>? {
        if (cacheKey.isEmpty() || fileSize <= 0L) return null
        val file = fileFor(cacheKey, charset)
        if (!file.isFile || file.length() <= 0L) return null
        val body = runCatching {
            json.decodeFromString(FileBody.serializer(), file.readText())
        }.getOrNull() ?: return null
        if (body.v != FORMAT_VERSION ||
            body.fileSize != fileSize ||
            body.charset != charset ||
            body.chapters.isEmpty()
        ) {
            return null
        }
        return body.chapters.map { EbookChapter(it.title, it.text, it.depth) }
    }

    /**
     * Last saved body when the share cannot be sized (offline history).
     * A live size still goes through [load], which misses when the file changed.
     */
    fun loadLast(cacheKey: String, charset: String = "auto"): List<EbookChapter>? {
        if (cacheKey.isEmpty()) return null
        val file = fileFor(cacheKey, charset)
        if (!file.isFile || file.length() <= 0L) return null
        val body = runCatching {
            json.decodeFromString(FileBody.serializer(), file.readText())
        }.getOrNull() ?: return null
        if (body.v != FORMAT_VERSION || body.charset != charset || body.chapters.isEmpty()) {
            return null
        }
        return body.chapters.map { EbookChapter(it.title, it.text, it.depth) }
    }

    fun save(cacheKey: String, fileSize: Long, chapters: List<EbookChapter>, charset: String = "auto") {
        if (cacheKey.isEmpty() || fileSize <= 0L || chapters.isEmpty()) return
        val lock = locks.computeIfAbsent("$cacheKey\u0000$charset") { Any() }
        synchronized(lock) {
            val dest = fileFor(cacheKey, charset)
            dest.parentFile?.mkdirs()
            val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
            try {
                val body = FileBody(
                    fileSize = fileSize,
                    charset = charset,
                    chapters = chapters.map { Chapter(it.title, it.text, it.depth) },
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

    internal fun fileFor(cacheKey: String, charset: String = "auto"): File = File(root, sha256Hex(identity(cacheKey, charset)) + ".json")

    /** Hash input. Deliberately not the raw path the image index or PDF TOC uses. */
    internal fun identity(cacheKey: String, charset: String = "auto"): String = "ebook-body:v$FORMAT_VERSION:$charset:$cacheKey"

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
