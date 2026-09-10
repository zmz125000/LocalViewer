package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

/**
 * Persistent mirror of process-scoped browse listings (SMB / WebDAV / local folder roots).
 *
 * **Identity:** one JSON file per source — `{protocol}_{sourceId}.json`
 * (e.g. `smb_7.json`). Editing host / share / user / URL on the **same** row keeps
 * this file. Stored `configKey` is only a stamp (updated on save); it must **not**
 * invalidate the whole index — slim quick scan marks stale dirs unreachable as the user
 * re-enters folders (descendant keys stay until a later slim hit recovers them).
 *
 * `folders[relativeDir]` holds the lazy scanner’s [BrowseEntryRemote] rows, including
 * embedded [BrowseEntryRemote.FolderGallery.imageFileNames] and local
 * [BrowseEntryRemote.ArchiveGallery.pageCount]. Zip/cbz-as-dir interiors
 * use the same keys (`dir/file.zip`, `dir/file.zip/Album`). [FolderGalleryIndex] only
 * *reads* these listings (and RAM) — it does not write a separate gallery cache.
 *
 * A cache hit returns the scanner's final values; this layer never re-classifies.
 * Saves for a key run through [preferCompleteFolderGalleries] against any prior value
 * so a poorer re-list cannot wipe complete page names.
 *
 * Disk loads hydrate into [BrowseSession] as **non-current**. Only a successful full/slim
 * list for that exact directory marks the RAM entry current; quick scan then skips
 * current dirs and re-runs for every old dir (including subfolders).
 *
 * Local folder roots use protocol `local` with [LibraryRootEntity.id] as [sourceId].
 * Lives under [appCtx.noBackupFilesDir] so Android cache GC / [OriginDiskCache] trim
 * cannot delete it. Legacy files under [appCtx.cacheDir] are copied on first load/save.
 */
object NetworkFolderIndexCache {
    /** Bump when on-disk entry shape changes — old JSON is ignored (no migration). */
    private const val VERSION = 5
    private const val KIND_DIRECTORY = "directory"
    private const val KIND_FOLDER_GALLERY = "folder_gallery"
    private const val KIND_ARCHIVE = "archive"
    private const val KIND_VIDEO = "video"
    private const val KIND_FILE = "file"

    private val lock = Mutex()
    private val cacheDir: File
        get() = File(appCtx.noBackupFilesDir, "network_folder_index")
    private val legacyCacheDir: File
        get() = File(appCtx.cacheDir, "network_folder_index")

    suspend fun loadSmb(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("smb", sourceId, configKey, relativeDir)

    /** @return entries actually stored (may retain prior complete gallery page lists). */
    suspend fun saveSmb(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ): List<BrowseEntryRemote> = save("smb", sourceId, configKey, relativeDir, entries, removedChildDirs)

    suspend fun loadWebDav(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("webdav", sourceId, configKey, relativeDir)

    /** @return entries actually stored (may retain prior complete gallery page lists). */
    suspend fun saveWebDav(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ): List<BrowseEntryRemote> = save("webdav", sourceId, configKey, relativeDir, entries, removedChildDirs)

    suspend fun loadLocal(
        rootId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("local", rootId, configKey, relativeDir)

    /** @return entries actually stored (may retain prior complete gallery page lists). */
    suspend fun saveLocal(
        rootId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ): List<BrowseEntryRemote> = save("local", rootId, configKey, relativeDir, entries, removedChildDirs)

    /** All folder keys currently stored for a local root (one JSON parse). */
    suspend fun loadLocalFolders(
        rootId: Long,
        configKey: String,
    ): Map<String, List<BrowseEntryRemote>> = loadAllFolders("local", rootId, configKey)

    /**
     * Apply many local folder listings and write the JSON **once**.
     * Keys not present in [folders] are left unchanged.
     */
    suspend fun saveLocalAll(
        rootId: Long,
        configKey: String,
        folders: Map<String, List<BrowseEntryRemote>>,
    ): Map<String, List<BrowseEntryRemote>> = saveAll("local", rootId, configKey, folders)

    suspend fun deleteSmb(sourceId: Long) = delete("smb", sourceId)

    suspend fun deleteWebDav(sourceId: Long) = delete("webdav", sourceId)

    suspend fun deleteLocal(rootId: Long) = delete("local", rootId)

    suspend fun removeSmbUnder(sourceId: Long, relativeDir: String) = removeUnder("smb", sourceId, relativeDir)

    suspend fun removeWebDavUnder(sourceId: Long, relativeDir: String) = removeUnder("webdav", sourceId, relativeDir)

    suspend fun removeLocalUnder(rootId: Long, relativeDir: String) = removeUnder("local", rootId, relativeDir)

    /** Drop every protocol file (current + legacy cacheDir) and process RAM listings. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            deleteDirContents(cacheDir)
            deleteDirContents(legacyCacheDir)
        }
        BrowseSession.invalidateLocalListing()
        BrowseSession.invalidateAllSmbListings()
        BrowseSession.invalidateAllWebDavListings()
    }

    private suspend fun load(
        protocol: String,
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext null
        lock.withLock {
            val file = fileFor(protocol, sourceId)
            val root = readRoot(file) ?: return@withLock null
            // File identity is protocol+sourceId only. configKey mismatch (edited host/
            // share/user) must not discard the index — slim cleans stale paths later.
            if (!matchesVersion(root)) return@withLock null
            val storedKey = root.optString("configKey")
            if (storedKey.isNotEmpty() && storedKey != configKey) {
                logcat("FolderIndex") {
                    "Keeping $protocol/$sourceId index after source edit (configKey stamp differs)"
                }
            }
            val array = root.optJSONObject("folders")
                ?.optJSONArray(normalizeDir(relativeDir))
                ?: return@withLock null
            val decoded = runCatching { decodeEntries(array) }
                .onFailure { logcat("FolderIndex", it) }
                .getOrNull()
                ?: return@withLock null
            file.setLastModified(System.currentTimeMillis())
            decoded
        }
    }

    private suspend fun loadAllFolders(
        protocol: String,
        sourceId: Long,
        configKey: String,
    ): Map<String, List<BrowseEntryRemote>> = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext emptyMap()
        lock.withLock {
            val file = fileFor(protocol, sourceId)
            val root = readRoot(file) ?: return@withLock emptyMap()
            if (!matchesVersion(root)) return@withLock emptyMap()
            val storedKey = root.optString("configKey")
            if (storedKey.isNotEmpty() && storedKey != configKey) {
                logcat("FolderIndex") {
                    "Keeping $protocol/$sourceId index after source edit (configKey stamp differs)"
                }
            }
            val array = root.optJSONObject("folders") ?: return@withLock emptyMap()
            val out = LinkedHashMap<String, List<BrowseEntryRemote>>()
            val keys = array.keys()
            while (keys.hasNext()) {
                val keyName = keys.next()
                val decoded = runCatching { decodeEntries(array.getJSONArray(keyName)) }
                    .onFailure { logcat("FolderIndex", it) }
                    .getOrNull()
                    ?: continue
                out[keyName] = decoded
            }
            if (out.isNotEmpty()) file.setLastModified(System.currentTimeMillis())
            out
        }
    }

    private suspend fun save(
        protocol: String,
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String>,
    ): List<BrowseEntryRemote> = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext entries
        lock.withLock {
            val file = fileFor(protocol, sourceId)
            val root = mutableRoot(file, configKey)
            val folders = root.getJSONObject("folders")
            val zipAsDir = Settings.browseZipAsDir.value
            val (toStore, dirty) = mergeFolderEntry(
                folders,
                relativeDir,
                entries,
                removedChildDirs,
                zipAsDir,
                logKeep = "$protocol/$sourceId",
            )
            if (dirty) writeRootFile(file, root)
            toStore
        }
    }

    private suspend fun saveAll(
        protocol: String,
        sourceId: Long,
        configKey: String,
        updates: Map<String, List<BrowseEntryRemote>>,
    ): Map<String, List<BrowseEntryRemote>> = withContext(Dispatchers.IO) {
        if (updates.isEmpty()) return@withContext emptyMap()
        if (!Settings.networkFolderIndexCache.value) return@withContext updates
        lock.withLock {
            val file = fileFor(protocol, sourceId)
            val root = mutableRoot(file, configKey)
            val folders = root.getJSONObject("folders")
            val zipAsDir = Settings.browseZipAsDir.value
            val stored = LinkedHashMap<String, List<BrowseEntryRemote>>(updates.size)
            var dirty = false
            for ((relativeDir, entries) in updates) {
                val (toStore, changed) = mergeFolderEntry(
                    folders,
                    relativeDir,
                    entries,
                    removedChildDirs = emptySet(),
                    zipAsDir = zipAsDir,
                    logKeep = "$protocol/$sourceId",
                )
                stored[normalizeDir(relativeDir)] = toStore
                dirty = dirty || changed
            }
            if (dirty) writeRootFile(file, root)
            stored
        }
    }

    private fun mutableRoot(file: File, configKey: String): JSONObject {
        // Reuse folders JSON across source edits (same id); only VERSION must match.
        val existing = readRoot(file)?.takeIf { matchesVersion(it) }
        val root = existing ?: JSONObject().apply {
            put("version", VERSION)
            put("folders", JSONObject())
        }
        root.put("version", VERSION)
        root.put("configKey", configKey) // stamp only — not a load gate
        if (!root.has("folders")) root.put("folders", JSONObject())
        return root
    }

    /**
     * @return stored listing and whether [folders] changed enough to rewrite the file.
     */
    private fun mergeFolderEntry(
        folders: JSONObject,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String>,
        zipAsDir: Boolean,
        logKeep: String,
    ): Pair<List<BrowseEntryRemote>, Boolean> {
        val key = normalizeDir(relativeDir)
        val previous = folders.optJSONArray(key)?.let { array ->
            runCatching { decodeEntries(array) }.getOrNull()
        }
        val keepPrevious = previous != null &&
            shouldKeepPreviousFolderIndex(previous, entries, zipAsDir)
        var removed = false
        if (!keepPrevious && removedChildDirs.isNotEmpty()) {
            val parent = normalizeDir(relativeDir)
            val removedPrefixes = removedChildDirs.map { child ->
                listOf(parent, normalizeDir(child)).filter { it.isNotEmpty() }.joinToString("/")
            }
            val staleKeys = buildList {
                val keys = folders.keys()
                while (keys.hasNext()) {
                    val keyName = keys.next()
                    if (removedPrefixes.any { prefix ->
                            keyName == prefix || keyName.startsWith("$prefix/")
                        }
                    ) {
                        add(keyName)
                    }
                }
            }
            if (staleKeys.isNotEmpty()) {
                staleKeys.forEach { folders.remove(it) }
                removed = true
            }
        }
        val toStore = if (keepPrevious) {
            logcat("FolderIndex") {
                "Keeping $logKeep dir=$key index " +
                    "(new listing empty/shallow or dropped every folder)"
            }
            checkNotNull(previous)
        } else if (previous != null) {
            preferCompleteFolderGalleries(previous, entries)
        } else {
            entries
        }
        val unchanged = previous != null && toStore == previous
        if (!unchanged) {
            folders.put(key, encodeEntries(toStore))
        }
        return toStore to (removed || !unchanged)
    }

    private fun writeRootFile(file: File, root: JSONObject) {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${file.name}.tmp.${System.nanoTime()}")
        try {
            tmp.writeText(root.toString())
            if (CachePagePublish.atomicReplaceFile(tmp, file)) {
                file.setLastModified(System.currentTimeMillis())
                File(legacyCacheDir, file.name).delete()
            }
        } catch (e: Throwable) {
            logcat("FolderIndex", e)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Drop [relativeDir] and nested keys (`dir/file.zip`, `dir/file.zip/Album`).
     * Empty [relativeDir] is ignored so a whole source index is never wiped.
     */
    private suspend fun removeUnder(
        protocol: String,
        sourceId: Long,
        relativeDir: String,
    ) = withContext(Dispatchers.IO) {
        val prefix = normalizeDir(relativeDir)
        if (prefix.isEmpty()) return@withContext
        if (!Settings.networkFolderIndexCache.value) return@withContext
        lock.withLock {
            val file = fileFor(protocol, sourceId)
            val root = readRoot(file)?.takeIf { matchesVersion(it) } ?: return@withLock
            val folders = root.optJSONObject("folders") ?: return@withLock
            val stale = buildList {
                val keys = folders.keys()
                while (keys.hasNext()) {
                    val keyName = keys.next()
                    if (keyName == prefix || keyName.startsWith("$prefix/")) add(keyName)
                }
            }
            if (stale.isEmpty()) return@withLock
            stale.forEach { folders.remove(it) }
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "${file.name}.tmp.${System.nanoTime()}")
            try {
                tmp.writeText(root.toString())
                if (CachePagePublish.atomicReplaceFile(tmp, file)) {
                    file.setLastModified(System.currentTimeMillis())
                    File(legacyCacheDir, file.name).delete()
                }
            } catch (e: Throwable) {
                logcat("FolderIndex", e)
            } finally {
                tmp.delete()
            }
        }
    }

    private suspend fun delete(protocol: String, sourceId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val name = "${protocol}_$sourceId.json"
            File(cacheDir, name).delete()
            File(legacyCacheDir, name).delete()
            deleteTmpFiles(cacheDir, name)
            deleteTmpFiles(legacyCacheDir, name)
        }
    }

    private fun deleteDirContents(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun deleteTmpFiles(dir: File, jsonName: String) {
        if (!dir.isDirectory) return
        val prefix = "$jsonName.tmp."
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix)) f.delete()
        }
    }

    private fun fileFor(protocol: String, sourceId: Long): File {
        val dest = File(cacheDir, "${protocol}_$sourceId.json")
        if (dest.isFile && dest.length() > 0L) return dest
        val legacy = File(legacyCacheDir, "${protocol}_$sourceId.json")
        if (legacy.isFile && legacy.length() > 0L) {
            cacheDir.mkdirs()
            runCatching { legacy.copyTo(dest, overwrite = false) }
                .onFailure { logcat("FolderIndex", it) }
        }
        return dest
    }

    private fun normalizeDir(relativeDir: String) = relativeDir.replace('\\', '/').trim('/')

    private fun readRoot(file: File): JSONObject? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { JSONObject(file.readText()) }
            .onFailure { logcat("FolderIndex", it) }
            .getOrNull()
    }

    private fun matchesVersion(root: JSONObject): Boolean = root.optInt("version", -1) == VERSION

    private fun encodeEntries(entries: List<BrowseEntryRemote>) = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("hidden", entry.hidden)
                    put("virtual", entry.virtual)
                    when (entry) {
                        is BrowseEntryRemote.Directory -> {
                            put("kind", KIND_DIRECTORY)
                            put("relativeName", entry.relativeName)
                            put("hasVideo", entry.hasVideo)
                            put("hasGallery", entry.hasGallery)
                            put("presence", entry.presence.name)
                            entry.coverFileName?.let { put("coverFileName", it) }
                            if (entry.lastModifiedMs > 0L) put("lastModifiedMs", entry.lastModifiedMs)
                            if (entry.size > 0L) put("size", entry.size)
                            if (entry.unreachable) put("unreachable", true)
                            if (entry.zipStale) put("zipStale", true)
                        }
                        is BrowseEntryRemote.FolderGallery -> {
                            put("kind", KIND_FOLDER_GALLERY)
                            put("relativeName", entry.relativeName)
                            put("pageCount", entry.pageCount)
                            put("pageCountCapped", entry.pageCountCapped)
                            entry.coverFileName?.let { put("coverFileName", it) }
                            put("imageFileNames", JSONArray(entry.imageFileNames))
                        }
                        is BrowseEntryRemote.ArchiveGallery -> {
                            put("kind", KIND_ARCHIVE)
                            put("fileName", entry.fileName)
                            put("parentRelativeName", entry.parentRelativeName)
                            if (entry.size > 0L) put("size", entry.size)
                            if (entry.lastModifiedMs > 0L) put("lastModifiedMs", entry.lastModifiedMs)
                            if (entry.pageCount > 0) put("pageCount", entry.pageCount)
                        }
                        is BrowseEntryRemote.VideoFile -> {
                            put("kind", KIND_VIDEO)
                            put("fileName", entry.fileName)
                            if (entry.size > 0L) put("size", entry.size)
                            if (entry.lastModifiedMs > 0L) put("lastModifiedMs", entry.lastModifiedMs)
                        }
                        is BrowseEntryRemote.RegularFile -> {
                            put("kind", KIND_FILE)
                            put("fileName", entry.fileName)
                            if (entry.size > 0L) put("size", entry.size)
                            if (entry.lastModifiedMs > 0L) put("lastModifiedMs", entry.lastModifiedMs)
                        }
                    }
                },
            )
        }
    }

    private fun decodeEntries(array: JSONArray): List<BrowseEntryRemote> = buildList(array.length()) {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val name = item.getString("name")
            val hidden = item.optBoolean("hidden")
            val virtual = item.optBoolean("virtual")
            add(
                when (item.getString("kind")) {
                    KIND_DIRECTORY -> BrowseEntryRemote.Directory(
                        name = name,
                        relativeName = item.optString("relativeName", name),
                        hasVideo = item.optBoolean("hasVideo"),
                        hasGallery = item.optBoolean("hasGallery"),
                        presence = DirPresence.valueOf(item.getString("presence")),
                        coverFileName = item.optNullableString("coverFileName"),
                        lastModifiedMs = item.optLong("lastModifiedMs"),
                        size = item.optLong("size"),
                        hidden = hidden,
                        virtual = virtual,
                        unreachable = item.optBoolean("unreachable"),
                        zipStale = item.optBoolean("zipStale"),
                    )
                    KIND_FOLDER_GALLERY -> BrowseEntryRemote.FolderGallery(
                        name = name,
                        relativeName = item.getString("relativeName"),
                        pageCount = item.getInt("pageCount"),
                        pageCountCapped = item.optBoolean("pageCountCapped"),
                        coverFileName = item.optNullableString("coverFileName"),
                        imageFileNames = item.optJSONArray("imageFileNames").toStringList(),
                        hidden = hidden,
                        virtual = virtual,
                    )
                    KIND_ARCHIVE -> BrowseEntryRemote.ArchiveGallery(
                        name = name,
                        fileName = item.getString("fileName"),
                        parentRelativeName = item.optString("parentRelativeName"),
                        size = item.optLong("size"),
                        lastModifiedMs = item.optLong("lastModifiedMs"),
                        pageCount = item.optInt("pageCount"),
                        hidden = hidden,
                        virtual = virtual,
                    )
                    KIND_VIDEO -> BrowseEntryRemote.VideoFile(
                        name = name,
                        fileName = item.optString("fileName", name),
                        size = item.optLong("size"),
                        lastModifiedMs = item.optLong("lastModifiedMs"),
                        hidden = hidden,
                        virtual = virtual,
                    )
                    KIND_FILE -> BrowseEntryRemote.RegularFile(
                        name = name,
                        fileName = item.optString("fileName", name),
                        size = item.optLong("size"),
                        lastModifiedMs = item.optLong("lastModifiedMs"),
                        hidden = hidden,
                        virtual = virtual,
                    )
                    else -> error("Unknown network folder index entry")
                },
            )
        }
    }

    private fun JSONObject.optNullableString(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { getString(it) }
    }
}
