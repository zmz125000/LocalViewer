package com.hippo.ehviewer.smb

import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

object SmbCache {
    private val root: Path
        get() = appCtx.cacheDir.resolve("smb_cache").toOkioPath().also { it.mkdirs() }

    /** One lock per cache file so prefetch + onRequest never race the same .tmp. */
    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimLock = Mutex()
    private val trimScheduled = AtomicBoolean(false)

    fun cachePath(sourceId: Long, remoteRelativePath: String, fileName: String): Path {
        val key = "$sourceId:$remoteRelativePath/$fileName"
        val hash = sha256Hex(key)
        val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase() ?: "bin"
        return root / "$hash.$ext"
    }

    fun isCached(path: Path) = path.exists()

    /**
     * Download into [path] if missing. Concurrent callers for the same path share one download;
     * commit is atomic so a second writer never fails with "Failed to commit".
     */
    suspend fun downloadIfNeeded(path: Path, write: suspend (OutputStream) -> Unit) {
        if (isCached(path)) {
            touch(path)
            return
        }
        val key = path.toString()
        val mutex = pathLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (isCached(path)) {
                touch(path)
                return
            }
            path.parent?.mkdirs()
            val dest = File(key)
            // Unique tmp so concurrent different files (or a stale .tmp) never collide.
            val tmp = File("$key.tmp.${System.nanoTime()}")
            try {
                FileOutputStream(tmp).use { out -> write(out) }
                commitTmp(tmp, dest)
                touch(path)
            } catch (e: Throwable) {
                tmp.delete()
                // Another writer may have finished while we failed.
                if (isCached(path)) return
                throw e
            }
        }
        scheduleTrim()
    }

    /** Bump mtime so LRU eviction prefers older pages. */
    private fun touch(path: Path) {
        val f = File(path.toString())
        if (f.isFile) f.setLastModified(System.currentTimeMillis())
    }

    private fun scheduleTrim() {
        if (!trimScheduled.compareAndSet(false, true)) return
        // Fire-and-forget on IO; next download can schedule again after this finishes.
        trimScope.launch {
            try {
                trimToMaxSize()
            } finally {
                trimScheduled.set(false)
            }
        }
    }

    /**
     * Evict oldest files in `smb_cache` until total size ≤ [Settings.readCacheSize] MiB.
     * Same budget as legacy EH `image_cache` (Advanced → Image disk cache).
     */
    suspend fun trimToMaxSize() = withContext(Dispatchers.IO) {
        trimLock.withLock {
            val maxBytes = Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024L * 1024L
            val dir = File(root.toString())
            if (!dir.isDirectory) return@withLock
            val files = dir.listFiles { f -> f.isFile && !f.name.contains(".tmp.") }?.toMutableList()
                ?: return@withLock
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return@withLock
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= maxBytes) break
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    pathLocks.remove(f.absolutePath)
                }
            }
        }
    }

    private fun commitTmp(tmp: File, dest: File) {
        if (!tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            error("SMB download produced empty temp file for ${dest.name}")
        }
        // Fast path.
        if (tmp.renameTo(dest)) return
        // Destination already present (lost the race after re-check, or rename semantics).
        if (dest.isFile && dest.length() > 0L) {
            tmp.delete()
            return
        }
        try {
            try {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (dest.isFile && dest.length() > 0L) return
            throw IllegalStateException("Failed to commit SMB cache for ${dest.name}", e)
        }
        if (!dest.isFile || dest.length() == 0L) {
            error("Failed to commit SMB cache for ${dest.name}")
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
