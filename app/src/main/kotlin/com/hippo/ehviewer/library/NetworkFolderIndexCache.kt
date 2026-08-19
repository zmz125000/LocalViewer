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
 * Persistent mirror of the process-scoped SMB/WebDAV browse listings.
 *
 * Each configured network source owns one JSON file containing every folder that has
 * completed the existing lazy scanner. A cache hit returns the scanner's final
 * [BrowseEntryRemote] values; it never runs or changes classification itself.
 *
 * Disk loads are hydrated into [BrowseSession] as **non-current** (old for this process).
 * Only a successful full/slim list for that exact directory marks the RAM entry current;
 * quick scan then skips current dirs and re-runs for every old dir (including subfolders).
 */
object NetworkFolderIndexCache {
    /** Bump when on-disk entry shape changes — old JSON is ignored (no migration). */
    private const val VERSION = 2
    private const val KIND_DIRECTORY = "directory"
    private const val KIND_FOLDER_GALLERY = "folder_gallery"
    private const val KIND_ARCHIVE = "archive"
    private const val KIND_VIDEO = "video"
    private const val KIND_FILE = "file"

    private val lock = Mutex()
    private val cacheDir: File
        get() = File(appCtx.cacheDir, "network_folder_index")

    suspend fun loadSmb(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("smb", sourceId, configKey, relativeDir)

    suspend fun saveSmb(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ) = save("smb", sourceId, configKey, relativeDir, entries, removedChildDirs)

    suspend fun loadWebDav(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
    ): List<BrowseEntryRemote>? = load("webdav", sourceId, configKey, relativeDir)

    suspend fun saveWebDav(
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String> = emptySet(),
    ) = save("webdav", sourceId, configKey, relativeDir, entries, removedChildDirs)

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
            if (!matches(root, configKey)) return@withLock null
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

    private suspend fun save(
        protocol: String,
        sourceId: Long,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        removedChildDirs: Set<String>,
    ) = withContext(Dispatchers.IO) {
        if (!Settings.networkFolderIndexCache.value) return@withContext
        lock.withLock {
            val file = fileFor(protocol, sourceId)
            val existing = readRoot(file)?.takeIf { matches(it, configKey) }
            val root = existing ?: JSONObject().apply {
                put("version", VERSION)
                put("configKey", configKey)
                put("folders", JSONObject())
            }
            val folders = root.getJSONObject("folders")
            if (removedChildDirs.isNotEmpty()) {
                val parent = normalizeDir(relativeDir)
                val removedPrefixes = removedChildDirs.map { child ->
                    listOf(parent, normalizeDir(child)).filter { it.isNotEmpty() }.joinToString("/")
                }
                val staleKeys = buildList {
                    val keys = folders.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (removedPrefixes.any { prefix -> key == prefix || key.startsWith("$prefix/") }) {
                            add(key)
                        }
                    }
                }
                staleKeys.forEach { folders.remove(it) }
            }
            folders.put(normalizeDir(relativeDir), encodeEntries(entries))
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "${file.name}.tmp.${System.nanoTime()}")
            try {
                tmp.writeText(root.toString())
                if (CachePagePublish.atomicReplaceFile(tmp, file)) {
                    file.setLastModified(System.currentTimeMillis())
                    OriginDiskCache.scheduleTrim()
                }
            } catch (e: Throwable) {
                logcat("FolderIndex", e)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun fileFor(protocol: String, sourceId: Long) = File(cacheDir, "${protocol}_$sourceId.json")

    private fun normalizeDir(relativeDir: String) = relativeDir.replace('\\', '/').trim('/')

    private fun readRoot(file: File): JSONObject? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { JSONObject(file.readText()) }
            .onFailure { logcat("FolderIndex", it) }
            .getOrNull()
    }

    private fun matches(root: JSONObject, configKey: String): Boolean = root.optInt("version", -1) == VERSION && root.optString("configKey") == configKey

    private fun encodeEntries(entries: List<BrowseEntryRemote>) = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject().apply {
                    put("name", entry.name)
                    when (entry) {
                        is BrowseEntryRemote.Directory -> {
                            put("kind", KIND_DIRECTORY)
                            put("relativeName", entry.relativeName)
                            put("hasVideo", entry.hasVideo)
                            put("hasGallery", entry.hasGallery)
                            put("presence", entry.presence.name)
                            entry.coverFileName?.let { put("coverFileName", it) }
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
                        }
                        is BrowseEntryRemote.VideoFile -> {
                            put("kind", KIND_VIDEO)
                            put("fileName", entry.fileName)
                        }
                        is BrowseEntryRemote.RegularFile -> {
                            put("kind", KIND_FILE)
                            put("fileName", entry.fileName)
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
            add(
                when (item.getString("kind")) {
                    KIND_DIRECTORY -> BrowseEntryRemote.Directory(
                        name = name,
                        relativeName = item.optString("relativeName", name),
                        hasVideo = item.optBoolean("hasVideo"),
                        hasGallery = item.optBoolean("hasGallery"),
                        presence = DirPresence.valueOf(item.getString("presence")),
                        coverFileName = item.optNullableString("coverFileName"),
                    )
                    KIND_FOLDER_GALLERY -> BrowseEntryRemote.FolderGallery(
                        name = name,
                        relativeName = item.getString("relativeName"),
                        pageCount = item.getInt("pageCount"),
                        pageCountCapped = item.optBoolean("pageCountCapped"),
                        coverFileName = item.optNullableString("coverFileName"),
                        imageFileNames = item.optJSONArray("imageFileNames").toStringList(),
                    )
                    KIND_ARCHIVE -> BrowseEntryRemote.ArchiveGallery(
                        name = name,
                        fileName = item.getString("fileName"),
                        parentRelativeName = item.optString("parentRelativeName"),
                    )
                    KIND_VIDEO -> BrowseEntryRemote.VideoFile(
                        name = name,
                        fileName = item.optString("fileName", name),
                    )
                    KIND_FILE -> BrowseEntryRemote.RegularFile(
                        name = name,
                        fileName = item.optString("fileName", name),
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
