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
 * Durable extract cache for PDF/EPUB image-only document extract.
 *
 * Layout matches solid extract:
 * ```
 * {dataDir}/cache/document_extract/{sha256(cacheKey)}/
 *   index.json
 *   pages/000000.jpg
 * ```
 *
 * Budget = [Settings.readCacheSize] MiB, **own** pool (independent of solid/stream/smb).
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
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)
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
        /**
         * v1: early indexes; cover-only extract could persist a 1-member list and poison open.
         * v2+: page list is always from a full structure walk (reader), never coverOnly.
         */
        val v: Int = INDEX_VERSION,
        val cacheKey: String,
        val remoteSize: Long = 0L,
        val format: String = "unknown",
        val complete: Boolean = false,
        val members: List<Member> = emptyList(),
    )

    /** Minimum [Index.v] trusted for openFromIndex / complete-and-ready. */
    const val INDEX_VERSION: Int = 2
    const val MIN_USABLE_INDEX_VERSION: Int = 2

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
        touch(index.cacheKey)
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
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
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
        val tmp = File("${dest}.tmp.${System.nanoTime()}")
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

    fun writePageFromFile(cacheKey: String, index: Int, ext: String, srcFile: File): Path {
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
     * page files so PDF/EPUB can reopen with a cached page list without re-parse.
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
                    if (pageBytes <= 0L) return@mapNotNull null // index-only: keep
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
                    val tmp = File("${idxFile.path}.tmp.${System.nanoTime()}")
                    try {
                        tmp.writeText(
                            json.encodeToString(Index.serializer(), idx.copy(complete = false)),
                        )
                        if (!tmp.renameTo(idxFile)) {
                            tmp.copyTo(idxFile, overwrite = true)
                            tmp.delete()
                        }
                    } finally {
                        if (tmp.exists()) tmp.delete()
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
