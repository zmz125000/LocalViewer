package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * **Budget:** independent of [SmbCache] / WebDAV page cache and of fixed thumb stores.
 * Size limit = [Settings.readCacheSize] MiB (same numeric pref, **own** disk pool — e.g.
 * Advanced 1 GiB ⇒ smb_cache 1 GiB **and** solid_extract another 1 GiB).
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
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)

    /** Open reader sessions — never evict these hash dirs. */
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

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
        isCachedFile(pagePath(cacheKey, index, ext), ext = ext)

    fun isCachedFile(path: Path, ext: String = ""): Boolean {
        val f = File(path.toString())
        return CachePagePublish.isCompleteCachedFile(f, ext = ext)
    }

    /**
     * One readdir of `pages/` → set of page indices present (no per-file [File.length]).
     * Used to fast-forward solid skip without O(n) stats before new extracts.
     */
    fun cachedPageIndices(cacheKey: String): Set<Int> =
        listCachedPages(cacheKey).keys

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
        touch(cacheKey)
    }

    fun unpin(cacheKey: String) {
        pinnedKeys.remove(cacheKey)
        scheduleTrim()
    }

    /** Bump dir / index mtime so LRU prefers colder archives. */
    fun touch(cacheKey: String) {
        val now = System.currentTimeMillis()
        val dir = File(dirFor(cacheKey).toString())
        if (dir.isDirectory) dir.setLastModified(now)
        val idx = File(indexPath(cacheKey).toString())
        if (idx.isFile) idx.setLastModified(now)
    }

    /** LRU bump without blocking the reader open path. */
    fun touchAsync(cacheKey: String) {
        trimScope.launch { touch(cacheKey) }
    }

    fun writePage(cacheKey: String, index: Int, ext: String, buffer: ByteBuffer): Path {
        val dest = pagePath(cacheKey, index, ext)
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
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
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
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
        if (!trimScheduled.compareAndSet(false, true)) return
        trimScope.launch {
            try {
                trimToMaxSize()
            } finally {
                trimScheduled.set(false)
            }
        }
    }

    /**
     * Free page bytes until under budget. **Never deletes [index.json]** — only strips
     * `pages/` (and any other non-index files). Incomplete first, then oldest mtime.
     * Skip [pinnedKeys]. Budget = [Settings.readCacheSize] MiB (own pool).
     */
    suspend fun trimToMaxSize() = withContext(Dispatchers.IO) {
        trimLock.withLock {
            val budget = Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024L * 1024L
            val rootDir = File(root.toString())
            if (!rootDir.isDirectory) return@withLock

            data class Entry(
                val dir: File,
                val hash: String,
                val complete: Boolean,
                val mtime: Long,
                val pageBytes: Long,
            )

            val pinnedHashes = pinnedKeys.mapTo(HashSet()) { sha256Hex(it) }
            val entries = rootDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    val hash = dir.name
                    if (hash in pinnedHashes) return@mapNotNull null
                    val pageBytes = pageBytesOf(dir)
                    if (pageBytes <= 0L) return@mapNotNull null // index-only: keep forever
                    val idxFile = File(dir, "index.json")
                    val complete = if (idxFile.isFile && idxFile.length() > 0L) {
                        runCatching {
                            json.decodeFromString(Index.serializer(), idxFile.readText()).complete
                        }.getOrDefault(false)
                    } else {
                        false
                    }
                    val mtime = maxOf(dir.lastModified(), idxFile.lastModified())
                    Entry(dir, hash, complete, mtime, pageBytes)
                }
                // Incomplete first (false < true), then older first.
                ?.sortedWith(compareBy<Entry> { it.complete }.thenBy { it.mtime }.thenBy { it.hash })
                ?: return@withLock

            var total = entries.sumOf { it.pageBytes } +
                pinnedKeys.sumOf { k ->
                    val d = File(dirFor(k).toString())
                    if (d.isDirectory) pageBytesOf(d) else 0L
                }
            if (total <= budget) return@withLock

            for (e in entries) {
                if (total <= budget) break
                val freed = stripPagesKeepIndex(e.dir)
                if (freed > 0L) total -= freed
            }
        }
    }

    /**
     * Delete extracted page files; rewrite index with `complete = false` when present.
     * @return bytes freed
     */
    private fun stripPagesKeepIndex(dir: File): Long {
        var freed = 0L
        val pagesDir = File(dir, "pages")
        if (pagesDir.isDirectory) {
            pagesDir.walkTopDown().forEach { f ->
                if (f.isFile && !f.name.contains(".tmp.")) {
                    freed += f.length()
                    f.delete()
                }
            }
            pagesDir.deleteRecursively()
        }
        // Leftover non-index files at root (legacy / stray).
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != "index.json" && !f.name.startsWith("index.json.") &&
                !f.name.contains(".tmp.")
            ) {
                freed += f.length()
                f.delete()
            }
        }
        val idxFile = File(dir, "index.json")
        if (idxFile.isFile && idxFile.length() > 0L) {
            runCatching {
                val idx = json.decodeFromString(Index.serializer(), idxFile.readText())
                if (idx.complete) {
                    val dest = idxFile
                    val tmp = File("${dest.path}.tmp.${System.nanoTime()}")
                    try {
                        tmp.writeText(
                            json.encodeToString(
                                Index.serializer(),
                                idx.copy(complete = false),
                            ),
                        )
                        CachePagePublish.atomicReplaceFile(tmp, dest)
                    } catch (_: Throwable) {
                        // Best-effort rewrite during trim.
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
        return freed
    }

    /** Page payload only (excludes index.json). */
    private fun pageBytesOf(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var sum = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.name != "index.json" && !f.name.startsWith("index.json.") &&
                !f.name.contains(".tmp.")
            ) {
                sum += f.length()
            }
        }
        return sum
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
