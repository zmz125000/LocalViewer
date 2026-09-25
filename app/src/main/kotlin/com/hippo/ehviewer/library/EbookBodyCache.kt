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
    private const val FORMAT_VERSION = 1

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
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    private data class Chapter(
        val title: String,
        val text: String,
        val depth: Int,
    )

    /** Null on miss, including a size change. */
    fun load(cacheKey: String, fileSize: Long): List<EbookChapter>? {
        if (cacheKey.isEmpty() || fileSize <= 0L) return null
        val file = fileFor(cacheKey)
        if (!file.isFile || file.length() <= 0L) return null
        val body = runCatching {
            json.decodeFromString(FileBody.serializer(), file.readText())
        }.getOrNull() ?: return null
        if (body.v != FORMAT_VERSION || body.fileSize != fileSize || body.chapters.isEmpty()) {
            return null
        }
        return body.chapters.map { EbookChapter(it.title, it.text, it.depth) }
    }

    fun save(cacheKey: String, fileSize: Long, chapters: List<EbookChapter>) {
        if (cacheKey.isEmpty() || fileSize <= 0L || chapters.isEmpty()) return
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            val dest = fileFor(cacheKey)
            dest.parentFile?.mkdirs()
            val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
            try {
                val body = FileBody(
                    fileSize = fileSize,
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

    internal fun fileFor(cacheKey: String): File = File(root, sha256Hex(identity(cacheKey)) + ".json")

    /** Hash input. Deliberately not the raw path the image index or PDF TOC uses. */
    internal fun identity(cacheKey: String): String = "ebook-body:v$FORMAT_VERSION:$cacheKey"

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
