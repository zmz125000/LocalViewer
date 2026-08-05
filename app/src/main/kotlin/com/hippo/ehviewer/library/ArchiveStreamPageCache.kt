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
 * **Budget:** shared origin pool via [OriginDiskCache] ([Settings.readCacheSize]).
 * Trim deletes page files by age only; **never deletes [index.json]**.
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
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Dir names under [root] for open readers — excluded from origin LRU. */
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
        fun hasFullSeekIndex(): Boolean = members.isNotEmpty() && members.all { it.hasSeek }
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

    fun isPageCached(cacheKey: String, index: Int, ext: String): Boolean = isCached(pagePath(cacheKey, index, ext), ext = ext)

    /**
     * One readdir of extract dir → index → ext for present page files (skip tmp/index).
     * Used to resume half-cache TAR without re-downloading bodies.
     */
    fun listCachedPages(cacheKey: String): Map<Int, String> {
        val dir = File(dirFor(cacheKey).toString())
        if (!dir.isDirectory) return emptyMap()
        val list = dir.list() ?: return emptyMap()
        val out = HashMap<Int, String>(list.size)
        for (name in list) {
            if (name == "index.json" || name.startsWith("index.json.") ||
                name.contains(".tmp.") || name.contains(".pub.")
            ) {
                continue
            }
            val dot = name.lastIndexOf('.')
            if (dot <= 0) continue
            val idx = name.substring(0, dot).toIntOrNull() ?: continue
            val ext = name.substring(dot + 1).ifBlank { "bin" }
            if (idx in out) continue
            val f = File(dir, name)
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

    /**
     * Reader [close] helper: never readdir on the calling thread (Compose dispose / main).
     *
     * When [memoryComplete] is false but the stream index is finished, optionally
     * [probeDiskForComplete] via [countPageFiles] on the IO trim scope so sessions that
     * only filled the last missing pages still flip `complete=true`.
     */
    fun saveIndexOnCloseAsync(
        index: Index,
        memoryComplete: Boolean,
        probeDiskForComplete: Boolean,
        expectedPageCount: Int,
    ) {
        trimScope.launch {
            val complete = when {
                memoryComplete || index.complete -> true
                probeDiskForComplete && expectedPageCount > 0 ->
                    countPageFiles(index.cacheKey) >= expectedPageCount
                else -> false
            }
            saveIndex(index.copy(complete = complete))
        }
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
        val tmp = File("$dest.tmp.${System.nanoTime()}")
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
        val tmp = File("$dest.tmp.${System.nanoTime()}")
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
        OriginDiskCache.scheduleTrim()
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
