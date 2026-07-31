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
 * Caches **extracted page images** from stream-opened archives (ZIP/TAR; not the archive file).
 * Keyed by remote identity + page index.
 *
 * Layout:
 * ```
 * {dataDir}/cache/archive_pages/{sha256(cacheKey)}/
 *   index.json          // page list (optional until first full open)
 *   0.jpg, 1.png, …
 * ```
 *
 * **Budget:** independent of [SmbCache] / WebDAV page cache, fixed thumbs, and
 * [SolidExtractCache] / [DocumentExtractCache]. Limit = [Settings.readCacheSize] MiB
 * (same numeric pref, own pool). Trim strips page files and **keeps [index.json]**.
 */
object ArchiveStreamPageCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/archive_pages").toOkioPath()
    }

    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

    @Serializable
    data class Member(
        val i: Int,
        val name: String = "",
        val ext: String,
        val uncSize: Long = 0L,
        /**
         * Stream seek offset: ZIP local-header start, or TAR member data start.
         * -1 = unknown (legacy index; must re-open native CD/header walk).
         */
        val offset: Long = -1L,
        /** Compressed (ZIP) or raw (TAR) length for readahead warm. */
        val compSize: Long = -1L,
        /**
         * ZIP compression method (0 store / 8 deflate). TAR always 0.
         * -1 = unknown.
         */
        val method: Int = -1,
    ) {
        val hasSeek: Boolean get() = offset >= 0L && uncSize > 0L
    }

    @Serializable
    data class Index(
        /**
         * v1: ext list only. v2+: optional [Member.offset]/[Member.compSize]/[Member.method]
         * so reopen can skip ZIP EOCD/CD or TAR header walk.
         */
        val v: Int = INDEX_VERSION,
        val cacheKey: String,
        val remoteSize: Long = 0L,
        /** "zip" | "tar" | "stream" (unknown / legacy). */
        val format: String = "stream",
        val complete: Boolean = false,
        val members: List<Member> = emptyList(),
    ) {
        /** True when every member has a usable random-seek offset. */
        fun hasFullSeekIndex(): Boolean =
            members.isNotEmpty() && members.all { it.hasSeek }
    }

    const val INDEX_VERSION: Int = 2

    fun dirFor(cacheKey: String): Path = root / sha256Hex(cacheKey)

    fun indexPath(cacheKey: String): Path = dirFor(cacheKey) / "index.json"

    fun pagePath(cacheKey: String, index: Int, ext: String): Path {
        val dir = dirFor(cacheKey)
        val safeExt = ext.lowercase().ifBlank { "bin" }.take(8)
        return dir / "$index.$safeExt"
    }

    fun isCached(path: Path, ext: String = ""): Boolean {
        val f = File(path.toString())
        return CachePagePublish.isCompleteCachedFile(f, ext = ext)
    }

    fun isPageCached(cacheKey: String, index: Int, ext: String): Boolean =
        isCached(pagePath(cacheKey, index, ext), ext = ext)

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

    fun saveIndexAsync(index: Index) {
        trimScope.launch { saveIndex(index) }
    }

    fun allPagesPresent(cacheKey: String, index: Index): Boolean {
        if (index.members.isEmpty()) return false
        return index.members.all { m -> isPageCached(cacheKey, m.i, m.ext) }
    }

    /**
     * Full offline open — **O(1) disk checks** (complete + first page + readdir count).
     * Per-page [File.length] on open made fully-cached reopen slower than cold network.
     *
     * If [index.json] still says `complete=false` but every page file is present (common
     * when the last session exited before [saveIndexAsync] flipped the flag), repair
     * `complete=true` and still take the offline path — avoids re-running TAR header
     * walk / ZIP CD open over the network.
     *
     * Pass [remoteSize] = 0 to skip remote-size match (offline-first before network stat).
     */
    fun isCompleteAndReady(cacheKey: String, remoteSize: Long = 0L): Index? {
        val idx = loadIndex(cacheKey) ?: return null
        if (remoteSize > 0L && idx.remoteSize > 0L && idx.remoteSize != remoteSize) {
            purge(cacheKey)
            return null
        }
        if (idx.members.isEmpty()) return null
        val first = idx.members.minBy { it.i }
        if (!isPageCached(cacheKey, first.i, first.ext)) return null
        val nFiles = countPageFiles(cacheKey)
        if (nFiles >= 0 && nFiles < idx.members.size) return null
        if (!idx.complete) {
            // Disk has a full page set; promote so next open skips the repair check path.
            saveIndexAsync(idx.copy(complete = true))
        }
        return if (idx.complete) idx else idx.copy(complete = true)
    }

    /** Non-index page files in the archive dir. -1 if unlistable. */
    fun countPageFiles(cacheKey: String): Int {
        val dir = File(dirFor(cacheKey).toString())
        if (!dir.isDirectory) return -1
        val list = dir.list() ?: return -1
        var n = 0
        for (name in list) {
            if (name == "index.json" || name.startsWith("index.json.") || name.contains(".tmp.")) {
                continue
            }
            n++
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

    fun extensionFor(cacheKey: String, index: Int): String? {
        loadIndex(cacheKey)?.members?.firstOrNull { it.i == index }?.ext?.let { return it }
        val dir = File(dirFor(cacheKey).toString())
        if (!dir.isDirectory) return null
        val prefix = "$index."
        return dir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.name != "index.json" }
            ?.name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
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
        ) { "Failed to publish stream cache page $index" }
        touch(cacheKey)
        scheduleTrim()
        return dest
    }

    /** TAR chunk extract — body already in memory from the same readahead window. */
    fun writePageBytes(cacheKey: String, index: Int, ext: String, bytes: ByteArray): Path {
        val dest = pagePath(cacheKey, index, ext)
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
        CachePagePublish.writeBytesToTmp(tmp, bytes)
        check(
            CachePagePublish.publishTmp(
                tmp = tmp,
                dest = File(dest.toString()),
                expectedSize = bytes.size.toLong(),
                ext = ext,
            ),
        ) { "Failed to publish stream cache page $index (bytes)" }
        touch(cacheKey)
        scheduleTrim()
        return dest
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
     * Evict page files (oldest / incomplete first); **keep [index.json]** for fast
     * offline reopen when pages are re-filled, and for member list after a strip.
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
                    if (pageBytes <= 0L) return@mapNotNull null
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

    private fun stripPagesKeepIndex(dir: File): Long {
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != "index.json" && !f.name.startsWith("index.json.") &&
                !f.name.contains(".tmp.")
            ) {
                freed += f.length()
                f.delete()
            } else if (f.isDirectory) {
                // Unexpected subdirs: drop contents but keep dir shell if needed.
                f.walkTopDown().forEach { child ->
                    if (child.isFile && !child.name.contains(".tmp.")) {
                        freed += child.length()
                        child.delete()
                    }
                }
                f.deleteRecursively()
            }
        }
        val idxFile = File(dir, "index.json")
        if (idxFile.isFile && idxFile.length() > 0L) {
            runCatching {
                val idx = json.decodeFromString(Index.serializer(), idxFile.readText())
                if (idx.complete) {
                    val tmp = File("${idxFile.path}.tmp.${System.nanoTime()}")
                    try {
                        tmp.writeText(
                            json.encodeToString(Index.serializer(), idx.copy(complete = false)),
                        )
                        CachePagePublish.atomicReplaceFile(tmp, idxFile)
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
