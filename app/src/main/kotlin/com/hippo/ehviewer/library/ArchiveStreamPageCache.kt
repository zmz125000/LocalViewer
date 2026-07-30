package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import java.io.File
import java.io.FileOutputStream
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
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Caches **extracted page images** from stream-opened archives (ZIP/TAR; not the archive file).
 * Keyed by remote identity + page index.
 *
 * **Budget:** independent of [SmbCache] / WebDAV page cache, fixed thumbs, and
 * [SolidExtractCache]. Limit = [Settings.readCacheSize] MiB (same numeric pref, own pool).
 */
object ArchiveStreamPageCache {
    private val root: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/archive_pages").toOkioPath()
    }

    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

    fun pagePath(cacheKey: String, index: Int, ext: String): Path {
        val dir = root / sha256Hex(cacheKey)
        val safeExt = ext.lowercase().ifBlank { "bin" }.take(8)
        return dir / "$index.$safeExt"
    }

    fun isCached(path: Path): Boolean {
        val f = File(path.toString())
        return f.isFile && f.length() > 0L
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
        val dir = File((root / sha256Hex(cacheKey)).toString())
        if (dir.isDirectory) dir.setLastModified(now)
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

    /** Evict whole archive page dirs (oldest first); skip pinned open archives. */
    suspend fun trimToMaxSize() = withContext(Dispatchers.IO) {
        trimLock.withLock {
            val budget = Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024L * 1024L
            val rootDir = File(root.toString())
            if (!rootDir.isDirectory) return@withLock

            data class Entry(val dir: File, val mtime: Long, val size: Long)

            val pinnedHashes = pinnedKeys.mapTo(HashSet()) { sha256Hex(it) }
            val entries = rootDir.listFiles()
                ?.filter { it.isDirectory && it.name !in pinnedHashes }
                ?.map { dir ->
                    Entry(dir, dir.lastModified(), dirSize(dir))
                }
                ?.filter { it.size > 0L }
                ?.sortedWith(compareBy<Entry> { it.mtime }.thenBy { it.dir.name })
                ?: return@withLock

            var total = entries.sumOf { it.size } +
                pinnedKeys.sumOf { k ->
                    val d = File((root / sha256Hex(k)).toString())
                    if (d.isDirectory) dirSize(d) else 0L
                }
            if (total <= budget) return@withLock

            for (e in entries) {
                if (total <= budget) break
                if (e.dir.deleteRecursively()) total -= e.size
            }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return if (dir.isFile) dir.length() else 0L
        var sum = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile && !f.name.contains(".tmp.")) sum += f.length()
        }
        return sum
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
