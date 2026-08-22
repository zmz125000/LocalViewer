package com.hippo.ehviewer.library

import java.io.File
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
 *
 * **Budget:** shared origin pool via [OriginDiskCache] ([Settings.readCacheSize]).
 * Trim deletes page files by age; **never deletes [index.json]**.
 */
object SolidExtractCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/solid_extract").toOkioPath()
    }

    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Open reader sessions — never evict these hash dirs. */
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

    fun isPageCached(cacheKey: String, index: Int, ext: String): Boolean = isCachedFile(pagePath(cacheKey, index, ext), ext = ext)

    fun isCachedFile(path: Path, ext: String = ""): Boolean {
        val f = File(path.toString())
        return CachePagePublish.isCompleteCachedFile(f, ext = ext)
    }

    /**
     * One readdir of `pages/` → set of page indices present (no per-file [File.length]).
     * Used to fast-forward solid skip without O(n) stats before new extracts.
     */
    fun cachedPageIndices(cacheKey: String): Set<Int> = listCachedPages(cacheKey).keys

    /**
     * `pages/%06d.ext` → (index → ext). Skips tmp files. Used to resume half-cache even
     * when `index.json` is missing or incomplete.
     */
    fun listCachedPages(cacheKey: String): Map<Int, String> {
        val pages = File((dirFor(cacheKey) / "pages").toString())
        if (!pages.isDirectory) return emptyMap()
        val list = pages.list() ?: return emptyMap()
        val out = HashMap<Int, String>(list.size)
        for (name in list) {
            if (name.contains(".tmp.") || name.contains(".pub.")) continue
            val dot = name.lastIndexOf('.')
            if (dot <= 0) continue
            val idx = name.substring(0, dot).toIntOrNull() ?: continue
            val ext = name.substring(dot + 1).ifBlank { "bin" }
            // Prefer non-empty length; first wins if duplicates.
            if (idx in out) continue
            val f = File(pages, name)
            if (f.isFile && f.length() >= CachePagePublish.MIN_PAGE_BYTES) {
                out[idx] = ext
            }
        }
        return out
    }

    fun loadIndex(cacheKey: String): Index? {
        val f = File(indexPath(cacheKey).toString())
        if (!f.isFile || f.length() == 0L) return null
        return runCatching {
            json.decodeFromString(Index.serializer(), f.readText())
        }.getOrNull()
    }

    /**
     * Serialize index writes per cache key so concurrent [saveIndexAsync] + extract
     * progress cannot race on the same `index.json.tmp.*` / rename path.
     */
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
                // Index persist must never crash the reader (half-cache still works from pages/).
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
     * Complete offline open — **O(1) disk checks**, not per-page [File.length].
     * Trusts [Index.complete] + first page + page-file count (one readdir).
     * Per-page probing made fully-cached reopen slower than a cold network open.
     */
    fun isCompleteAndReady(cacheKey: String, remoteSize: Long = 0L): Index? {
        if (remoteSize > 0L) {
            // Inline size check without full invalidate re-read when sizes match.
            val idx = loadIndex(cacheKey) ?: return null
            if (idx.remoteSize > 0L && idx.remoteSize != remoteSize) {
                purge(cacheKey)
                return null
            }
            return readyIfComplete(cacheKey, idx)
        }
        val idx = loadIndex(cacheKey) ?: return null
        return readyIfComplete(cacheKey, idx)
    }

    private fun readyIfComplete(cacheKey: String, idx: Index): Index? {
        if (!idx.complete || idx.members.isEmpty()) return null
        val first = idx.members.minBy { it.i }
        if (!isPageCached(cacheKey, first.i, first.ext)) return null
        val nFiles = countPageFiles(cacheKey)
        // readdir failed → still trust complete + first page (avoid O(n) fallback).
        if (nFiles >= 0 && nFiles < idx.members.size) return null
        return idx
    }

    /** Page files under `pages/` (excludes index / tmp). -1 if dir missing / unlistable. */
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

    /**
     * If [remoteSize] is known and differs from a stored index, purge the whole extract dir
     * so skip-write cannot mix old pages with a replaced remote file.
     *
     * @return true if a purge ran
     */
    fun invalidateIfRemoteSizeMismatch(cacheKey: String, remoteSize: Long): Boolean {
        if (remoteSize <= 0L) return false
        val idx = loadIndex(cacheKey) ?: return false
        if (idx.remoteSize <= 0L || idx.remoteSize == remoteSize) return false
        purge(cacheKey)
        return true
    }

    /** Delete index + pages for [cacheKey]. No-op if missing. */
    fun purge(cacheKey: String) {
        val dir = File(dirFor(cacheKey).toString())
        if (dir.exists()) dir.deleteRecursively()
    }

    /** Mark archive open — excluded from LRU until [unpin]. */
    fun pin(cacheKey: String) {
        pinnedKeys.add(cacheKey)
        // Pages are excluded from trim while pinned; bump page mtimes async so after
        // unpin [OriginDiskCache] still prefers this gallery over colder ones.
        touchAsync(cacheKey)
    }

    fun unpin(cacheKey: String) {
        pinnedKeys.remove(cacheKey)
        scheduleTrim()
    }

    /**
     * Bump dir / index mtime. When [includePages], also bump every page file —
     * [OriginDiskCache] sorts by page-file mtime, not the gallery dir.
     */
    fun touch(cacheKey: String, includePages: Boolean = false) {
        val now = System.currentTimeMillis()
        val dir = File(dirFor(cacheKey).toString())
        if (dir.isDirectory) {
            dir.setLastModified(now)
            if (includePages) touchPageFiles(dir, now)
        }
        val idx = File(indexPath(cacheKey).toString())
        if (idx.isFile) idx.setLastModified(now)
    }

    /** LRU bump (including page files) without blocking the reader open path. */
    fun touchAsync(cacheKey: String) {
        trimScope.launch { touch(cacheKey, includePages = true) }
    }

    private fun touchPageFiles(dir: File, now: Long) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            if (name == "index.json" || name.startsWith("index.json.") ||
                name.contains(".tmp.") || name.contains(".pub.")
            ) {
                continue
            }
            f.setLastModified(now)
        }
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
        ) { "Failed to publish solid cache page $index" }
        touch(cacheKey)
        scheduleTrim()
        return dest
    }

    fun writePageFromFdCopy(cacheKey: String, index: Int, ext: String, srcFile: File): Path {
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
        ) { "Failed to publish solid cache page $index from fd copy" }
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
