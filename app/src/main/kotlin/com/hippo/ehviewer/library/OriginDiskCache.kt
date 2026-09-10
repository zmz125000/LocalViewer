package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.webdav.WebDavCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Unified disk-cache policy for local-viewer origin images and browse thumbs.
 *
 * Network folder index metadata is preserved across this image LRU, like archive
 * `index.json` files. Other folder layouts are unchanged (smb_cache, webdav_cache,
 * zip_folder_pages, archive_pages, solid_extract, document_extract, *_thumb_cache,
 * archive_thumb, video_thumb_cache).
 *
 * 1. **Origin pics** (reader pages + remote folder/archive files): one shared cap =
 *    [Settings.readCacheSize] (Advanced “image disk cache”). No per-store pool.
 *    Oldest **page-file** mtime first (folder readers [SmbCache.isPageCachedOnDisk] /
 *    [WebDavCache.isPageCachedOnDisk] and extract [touch]/includePages=true)] bump
 *    on access). Never protects “complete” archive caches. **Never deletes
 *    `index.json`** under extract dirs.
 * 2. **Thumbs** (SMB/WebDAV/archive/video covers): long edge [THUMB_EDGE], fixed
 *    [THUMB_BUDGET_BYTES] — separate from origin budget and settings.
 */
object OriginDiskCache {
    /** Generated browse/cover thumb long edge (px). */
    const val THUMB_EDGE = 768

    /** Canonical on-disk thumb extension. Leftover `.jpg` files stay until LRU. */
    const val THUMB_EXT = "webp"

    const val THUMB_LEGACY_EXT = "jpg"

    /** Lossy WebP quality for new thumbs (JPEG leftovers keep their original bytes). */
    const val THUMB_QUALITY = 85

    /** Shared budget for all on-disk thumb stores (not Coil’s separate 256 MiB). */
    const val THUMB_BUDGET_BYTES = 512L * 1024L * 1024L

    fun thumbFileName(hash: String): String = "$hash.$THUMB_EXT"

    /** Same-hash leftover JPEG next to a canonical `.webp` thumb path. */
    fun jpegSibling(webpPath: Path): Path {
        val s = webpPath.toString()
        if (!s.endsWith(".$THUMB_EXT", ignoreCase = true)) return webpPath
        return File(s.substring(0, s.length - THUMB_EXT.length) + THUMB_LEGACY_EXT).toOkioPath()
    }

    fun jpegSibling(webpFile: File): File {
        val s = webpFile.path
        if (!s.endsWith(".$THUMB_EXT", ignoreCase = true)) return webpFile
        return File(s.substring(0, s.length - THUMB_EXT.length) + THUMB_LEGACY_EXT)
    }

    /**
     * Prefer a new WebP thumb; otherwise reuse a leftover JPEG of the same hash.
     * Does not encode. Null if neither file is present.
     */
    fun existingThumb(canonicalWebp: Path): Path? {
        val webp = File(canonicalWebp.toString())
        if (webp.isFile && webp.length() > 0L) return canonicalWebp
        val jpegPath = jpegSibling(canonicalWebp)
        if (jpegPath.toString() == canonicalWebp.toString()) return null
        val jpg = File(jpegPath.toString())
        return if (jpg.isFile && jpg.length() > 0L) jpegPath else null
    }

    fun existingThumb(canonicalWebp: File): File? {
        if (canonicalWebp.isFile && canonicalWebp.length() > 0L) return canonicalWebp
        val jpg = jpegSibling(canonicalWebp)
        if (jpg.path == canonicalWebp.path) return null
        return jpg.takeIf { it.isFile && it.length() > 0L }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val scheduled = AtomicBoolean(false)

    private val dataDir: String
        get() = appCtx.applicationInfo.dataDir

    private fun cacheDir(name: String): File = File(dataDir, "cache/$name")

    fun originBudgetBytes(): Long = Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024L * 1024L

    fun scheduleTrim() {
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                trimAll()
            } finally {
                scheduled.set(false)
            }
        }
    }

    suspend fun trimAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            trimOrigin()
            trimThumbs()
        }
    }

    // ── Origin (unified) ──────────────────────────────────────────────────

    private data class OriginEntry(
        val file: File,
        val mtime: Long,
        val size: Long,
        /** Extract-cache gallery dir that owns this page (for complete-flag rewrite). */
        val extractDir: File? = null,
    )

    private fun trimOrigin() {
        val budget = originBudgetBytes()
        val candidates = ArrayList<OriginEntry>(4096)
        var pinnedBytes = 0L

        // Flat remote file caches (folder pages + full archive downloads).
        collectFlatOrigin(cacheDir("smb_cache"), candidates)
        collectFlatOrigin(cacheDir("webdav_cache"), candidates)
        // Zip-as-dir extracted members (reader pages / optional thumb originals).
        collectFlatOrigin(cacheDir("zip_folder_pages"), candidates)
        // Local-folder HDR → Ultra HDR derivatives (non-destructive).
        collectFlatOrigin(cacheDir("hdr_ultrahdr"), candidates)

        // Extracted page caches — skip index.json; skip currently open galleries.
        pinnedBytes += collectExtractOrigin(
            cacheDir("archive_pages"),
            ArchiveStreamPageCache.pinnedDirHashes(),
            candidates,
        )
        pinnedBytes += collectExtractOrigin(
            cacheDir("solid_extract"),
            SolidExtractCache.pinnedDirHashes(),
            candidates,
        )
        pinnedBytes += collectExtractOrigin(
            cacheDir("document_extract"),
            DocumentExtractCache.pinnedDirHashes(),
            candidates,
        )

        var total = pinnedBytes + candidates.sumOf { it.size }
        if (total <= budget) return

        // Oldest pics first (stable name tie-break). No complete-archive preference.
        candidates.sortWith(compareBy<OriginEntry> { it.mtime }.thenBy { it.file.path })

        val dirtyExtractDirs = HashSet<String>()
        for (e in candidates) {
            if (total <= budget) break
            if (!e.file.isFile) continue
            if (e.file.delete()) {
                total -= e.size
                onOriginDeleted(e.file)
                e.extractDir?.let { dirtyExtractDirs.add(it.absolutePath) }
            }
        }
        for (path in dirtyExtractDirs) {
            markExtractIncomplete(File(path))
        }
    }

    private fun collectFlatOrigin(dir: File, out: MutableList<OriginEntry>) {
        if (!dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            if (name.contains(".tmp.") || name.contains(".full.") || name.contains(".jpg.")) continue
            val size = f.length()
            if (size <= 0L) continue
            out += OriginEntry(f, f.lastModified(), size)
        }
    }

    /**
     * @return total bytes in **pinned** extract dirs (counted toward budget, not evictable).
     */
    private fun collectExtractOrigin(
        root: File,
        pinnedHashes: Set<String>,
        out: MutableList<OriginEntry>,
    ): Long {
        if (!root.isDirectory) return 0L
        var pinnedBytes = 0L
        val dirs = root.listFiles() ?: return 0L
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val pinned = dir.name in pinnedHashes
            dir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                // Never trim archive indexes (or in-flight index writes).
                if (name == "index.json" || name.startsWith("index.json.")) return@forEach
                if (name.contains(".tmp.") || name.contains(".pub.")) return@forEach
                val size = f.length()
                if (size <= 0L) return@forEach
                if (pinned) {
                    pinnedBytes += size
                } else {
                    out += OriginEntry(f, f.lastModified(), size, extractDir = dir)
                }
            }
        }
        return pinnedBytes
    }

    private fun onOriginDeleted(file: File) {
        val path = file.absolutePath
        // Clear memory hit sets so readers re-download instead of ENOENT.
        val okio = file.toOkioPath() // okio Path from java.io.File
        when {
            path.contains("/cache/smb_cache/") -> SmbCache.markAbsent(okio)
            path.contains("/cache/webdav_cache/") -> WebDavCache.markAbsent(okio)
            // Extract caches re-probe with File.length — no memory set.
        }
    }

    /** After page eviction, clear `complete` so reopen does not claim full offline. */
    private fun markExtractIncomplete(dir: File) {
        val idxFile = File(dir, "index.json")
        if (!idxFile.isFile || idxFile.length() == 0L) return
        runCatching {
            val text = idxFile.readText()
            // Stream / solid / document indexes all have a `complete` field.
            if (!text.contains("\"complete\":true") && !text.contains("\"complete\": true")) return
            val tmp = File("${idxFile.path}.tmp.${System.nanoTime()}")
            try {
                // Prefer typed rewrite when possible; fall back to light string patch.
                val rewritten = when {
                    dir.parentFile?.name == "archive_pages" -> {
                        val idx = json.decodeFromString(ArchiveStreamPageCache.Index.serializer(), text)
                        if (!idx.complete) return
                        json.encodeToString(
                            ArchiveStreamPageCache.Index.serializer(),
                            idx.copy(complete = false),
                        )
                    }
                    dir.parentFile?.name == "solid_extract" -> {
                        val idx = json.decodeFromString(SolidExtractCache.Index.serializer(), text)
                        if (!idx.complete) return
                        json.encodeToString(
                            SolidExtractCache.Index.serializer(),
                            idx.copy(complete = false),
                        )
                    }
                    dir.parentFile?.name == "document_extract" -> {
                        val idx = json.decodeFromString(DocumentExtractCache.Index.serializer(), text)
                        if (!idx.complete) return
                        json.encodeToString(
                            DocumentExtractCache.Index.serializer(),
                            idx.copy(complete = false),
                        )
                    }
                    else -> return
                }
                tmp.writeText(rewritten)
                CachePagePublish.atomicReplaceFile(tmp, idxFile)
            } finally {
                tmp.delete()
            }
        }
    }

    // ── Thumbs (separate fixed budget) ────────────────────────────────────

    private data class ThumbEntry(val file: File, val mtime: Long, val size: Long)

    private fun trimThumbs() {
        val candidates = ArrayList<ThumbEntry>(2048)
        collectThumbFiles(cacheDir("smb_thumb_cache"), candidates)
        collectThumbFiles(cacheDir("webdav_thumb_cache"), candidates)
        collectThumbFiles(cacheDir("archive_thumb"), candidates)
        // Video frames (local + network) — same shared byte budget as other covers.
        collectThumbFiles(cacheDir("video_thumb_cache"), candidates)

        var total = candidates.sumOf { it.size }
        if (total <= THUMB_BUDGET_BYTES) return

        candidates.sortWith(compareBy<ThumbEntry> { it.mtime }.thenBy { it.file.path })
        for (e in candidates) {
            if (total <= THUMB_BUDGET_BYTES) break
            if (e.file.delete()) {
                total -= e.size
                onThumbDeleted(e.file)
            }
        }
    }

    private fun collectThumbFiles(dir: File, out: MutableList<ThumbEntry>) {
        if (!dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            // Skip temps and video-thumb failure markers (*.failed).
            if (name.contains(".tmp.") || name.contains(".jpg.") || name.contains(".webp.") ||
                name.contains(".uhdr.") || name.endsWith(".tmp") || name.endsWith(".failed")
            ) {
                continue
            }
            val size = f.length()
            if (size <= 0L) continue
            out += ThumbEntry(f, f.lastModified(), size)
        }
    }

    private fun onThumbDeleted(file: File) {
        val path = file.absolutePath
        val okio = file.toOkioPath()
        when {
            path.contains("/cache/smb_thumb_cache/") -> SmbCache.markAbsent(okio)
            path.contains("/cache/webdav_thumb_cache/") -> WebDavCache.markAbsent(okio)
            path.contains("/cache/archive_thumb/") -> ArchiveCoverCache.markAbsent(okio)
            // video_thumb_cache: no in-memory hit map to clear
        }
    }
}
