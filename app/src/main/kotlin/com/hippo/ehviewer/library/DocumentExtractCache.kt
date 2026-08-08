package com.hippo.ehviewer.library

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Durable extract cache for PDF/EPUB image-only document extract.
 *
 * Layout matches solid extract:
 * ```
 * {dataDir}/cache/document_extract/{sha256(cacheKey)}/
 *   index.json
 *   pages/000000.jpg
 * ```
 *
 * **Budget:** shared origin pool via [OriginDiskCache] ([Settings.readCacheSize]).
 * Trim deletes page files by age; **never deletes [index.json]**.
 */
object DocumentExtractCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/document_extract").toOkioPath()
    }

    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

    internal fun pinnedDirHashes(): Set<String> {
        if (pinnedKeys.isEmpty()) return emptySet()
        return pinnedKeys.mapTo(HashSet(pinnedKeys.size)) { sha256Hex(it) }
    }

    @Serializable
    data class Member(
        val i: Int,
        val name: String = "",
        val ext: String,
        val uncSize: Long = 0L,
        /**
         * File offset of the raw image stream payload (after `stream` keyword), or -1 unknown.
         * v3+: enables one Range extract without re-walking the PDF object graph.
         */
        val offset: Long = -1L,
    ) {
        val hasSeek: Boolean get() = offset >= 0L && uncSize > 0L
    }

    @Serializable
    data class Index(
        /**
         * v1: early indexes; cover-only extract could persist a 1-member list and poison open.
         * v2+: page list is always from a full structure walk (reader), never coverOnly.
         * v3+: optional [Member.offset] for direct stream Range extract.
         */
        val v: Int = INDEX_VERSION,
        val cacheKey: String,
        val remoteSize: Long = 0L,
        val format: String = "unknown",
        val complete: Boolean = false,
        val members: List<Member> = emptyList(),
    )

    /** Minimum [Index.v] trusted for openFromIndex / complete-and-ready. */
    const val INDEX_VERSION: Int = 3
    const val MIN_USABLE_INDEX_VERSION: Int = 2

    fun dirFor(cacheKey: String): Path = root / sha256Hex(cacheKey)

    fun indexPath(cacheKey: String): Path = dirFor(cacheKey) / "index.json"

    fun pagePath(cacheKey: String, index: Int, ext: String): Path {
        val safeExt = ext.lowercase().ifBlank { "bin" }.take(8)
        return dirFor(cacheKey) / "pages" / "%06d.%s".format(index, safeExt)
    }

    fun isPageCached(cacheKey: String, index: Int, ext: String): Boolean = isCachedFile(pagePath(cacheKey, index, ext), ext = ext)

    fun isCachedFile(path: Path, ext: String = ""): Boolean {
        val f = File(path.toString())
        return CachePagePublish.isCompleteCachedFile(f, ext = ext)
    }

    fun loadIndex(cacheKey: String): Index? {
        val f = File(indexPath(cacheKey).toString())
        if (!f.isFile || f.length() == 0L) return null
        return runCatching {
            json.decodeFromString(Index.serializer(), f.readText())
        }.getOrNull()
    }

    private val indexWriteLocks = ConcurrentHashMap<String, Any>()

    fun saveIndex(index: Index) {
        val lock = indexWriteLocks.computeIfAbsent(index.cacheKey) { Any() }
        synchronized(lock) {
            val dest = File(indexPath(index.cacheKey).toString())
            dest.parentFile?.mkdirs()
            val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
            try {
                tmp.writeText(json.encodeToString(Index.serializer(), index))
                CachePagePublish.atomicReplaceFile(tmp, dest)
            } catch (_: Throwable) {
                // Index persist must never crash the reader.
            } finally {
                tmp.delete()
            }
            touch(index.cacheKey)
        }
    }

    /** Persist index off the main thread (e.g. PageLoader.close / Compose onDispose). */
    fun saveIndexAsync(index: Index) {
        trimScope.launch { saveIndex(index) }
    }

    fun allPagesPresent(cacheKey: String, index: Index): Boolean {
        if (index.members.isEmpty()) return false
        return index.members.all { m -> isPageCached(cacheKey, m.i, m.ext) }
    }

    /**
     * Offline open — **O(1) disk checks** (complete flag + first page + readdir count).
     * Do not per-page [File.length] on open; that made cached reopen slower than cold.
     */
    fun isCompleteAndReady(cacheKey: String, remoteSize: Long = 0L): Index? {
        val idx = loadIndex(cacheKey) ?: return null
        if (idx.v < MIN_USABLE_INDEX_VERSION) return null
        if (remoteSize > 0L && idx.remoteSize > 0L && idx.remoteSize != remoteSize) {
            purge(cacheKey)
            return null
        }
        if (!idx.complete || idx.members.isEmpty()) return null
        val first = idx.members.minBy { it.i }
        if (!isPageCached(cacheKey, first.i, first.ext)) return null
        val nFiles = countPageFiles(cacheKey)
        if (nFiles >= 0 && nFiles < idx.members.size) return null
        return idx
    }

    /**
     * Index with a trustworthy page list and matching size — enough to skip structure re-parse.
     *
     * Rejects v1 indexes (cover-only could persist a 1-member list that made multi-page PDFs
     * open as 1 page via openFromIndex). One full re-parse upgrades to [INDEX_VERSION].
     */
    fun loadUsableIndex(cacheKey: String, remoteSize: Long = 0L): Index? {
        val idx = loadIndex(cacheKey) ?: return null
        if (idx.v < MIN_USABLE_INDEX_VERSION) return null
        if (idx.members.isEmpty()) return null
        if (remoteSize > 0L && idx.remoteSize > 0L && idx.remoteSize != remoteSize) {
            purge(cacheKey)
            return null
        }
        return idx
    }

    fun countPageFiles(cacheKey: String): Int {
        val pages = File((dirFor(cacheKey) / "pages").toString())
        if (!pages.isDirectory) return -1
        val list = pages.list() ?: return -1
        var n = 0
        for (name in list) {
            if (!name.contains(".tmp.") && name != "index.json") n++
        }
        return n
    }

    fun invalidateIfRemoteSizeMismatch(cacheKey: String, remoteSize: Long): Boolean {
        if (remoteSize <= 0L) return false
        val idx = loadIndex(cacheKey) ?: return false
        if (idx.remoteSize <= 0L || idx.remoteSize == remoteSize) return false
        purge(cacheKey)
        return true
    }

    fun purge(cacheKey: String) {
        val dir = File(dirFor(cacheKey).toString())
        if (dir.exists()) dir.deleteRecursively()
    }

    fun pin(cacheKey: String) {
        pinnedKeys.add(cacheKey)
        touch(cacheKey)
    }

    fun unpin(cacheKey: String) {
        pinnedKeys.remove(cacheKey)
        scheduleTrim()
    }

    fun touch(cacheKey: String) {
        val now = System.currentTimeMillis()
        val dir = File(dirFor(cacheKey).toString())
        if (dir.isDirectory) dir.setLastModified(now)
        val idx = File(indexPath(cacheKey).toString())
        if (idx.isFile) idx.setLastModified(now)
    }

    fun touchAsync(cacheKey: String) {
        trimScope.launch { touch(cacheKey) }
    }

    fun writePage(cacheKey: String, index: Int, ext: String, bytes: ByteArray): Path {
        val dest = pagePath(cacheKey, index, ext)
        val tmp = File("$dest.tmp.${System.nanoTime()}")
        CachePagePublish.writeBytesToTmp(tmp, bytes)
        check(
            CachePagePublish.publishTmp(
                tmp = tmp,
                dest = File(dest.toString()),
                expectedSize = bytes.size.toLong(),
                ext = ext,
            ),
        ) { "Failed to publish document cache page $index" }
        touch(cacheKey)
        scheduleTrim()
        return dest
    }

    fun writePage(cacheKey: String, index: Int, ext: String, buffer: ByteBuffer): Path {
        val dest = pagePath(cacheKey, index, ext)
        val tmp = File("$dest.tmp.${System.nanoTime()}")
        CachePagePublish.writeBufferToTmp(tmp, buffer)
        check(
            CachePagePublish.publishTmp(
                tmp = tmp,
                dest = File(dest.toString()),
                expectedSize = 0L,
                ext = ext,
            ),
        ) { "Failed to publish document cache page $index" }
        touch(cacheKey)
        scheduleTrim()
        return dest
    }

    /** Encode a rendered platform PDF page without retaining a second full-size byte array. */
    fun writePngPage(cacheKey: String, index: Int, bitmap: Bitmap): Path {
        val ext = "png"
        val dest = pagePath(cacheKey, index, ext)
        val tmp = File("$dest.tmp.${System.nanoTime()}")
        try {
            tmp.parentFile?.mkdirs()
            FileOutputStream(tmp).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Failed to encode rendered PDF page $index"
                }
            }
            check(
                CachePagePublish.publishTmp(
                    tmp = tmp,
                    dest = File(dest.toString()),
                    expectedSize = tmp.length(),
                    ext = ext,
                ),
            ) { "Failed to publish rendered PDF page $index" }
        } finally {
            tmp.delete()
        }
        touch(cacheKey)
        scheduleTrim()
        return dest
    }

    fun writePageFromFile(cacheKey: String, index: Int, ext: String, srcFile: File): Path {
        val dest = pagePath(cacheKey, index, ext)
        val tmp = File("$dest.tmp.${System.nanoTime()}")
        srcFile.copyTo(tmp, overwrite = true)
        check(
            CachePagePublish.publishTmp(
                tmp = tmp,
                dest = File(dest.toString()),
                expectedSize = srcFile.length().takeIf { it > 0L } ?: 0L,
                ext = ext,
            ),
        ) { "Failed to publish document cache page $index from file" }
        touch(cacheKey)
        scheduleTrim()
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

    fun scheduleTrim() {
        OriginDiskCache.scheduleTrim()
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
