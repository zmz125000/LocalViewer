package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk layout for one source's folder listings.
 *
 * `{sourceDir}/meta.json` — layout version + configKey stamp
 * `{sourceDir}/d/@.json` — listing for the source root (`relativeDir == ""`)
 * `{sourceDir}/d/{encoded}/.../{encoded}.json` — one listing per relativeDir
 *
 * Saving a folder writes only that listing file. Sibling folders are not rewritten
 * or re-parsed. Legacy v5 `{protocol}_{id}.json` blobs are split by [migrateFromV5Blob].
 */
internal class FolderIndexDisk(val sourceDir: File) {
    data class Meta(val version: Int, val configKey: String)

    fun readMeta(): Meta? {
        val file = metaFile(sourceDir)
        val root = readJsonObject(file) ?: return null
        val version = root.optInt("version", -1)
        if (version != LAYOUT_VERSION) return null
        return Meta(version, root.optString("configKey"))
    }

    fun writeMeta(configKey: String): Boolean {
        sourceDir.mkdirs()
        val root = JSONObject().apply {
            put("version", LAYOUT_VERSION)
            put("configKey", configKey)
        }
        return writeJsonFile(metaFile(sourceDir), root)
    }

    fun readListing(relativeDir: String): List<BrowseEntryRemote>? {
        val file = listingFile(sourceDir, relativeDir)
        return parseListingFile(file)?.entries
    }

    fun writeListing(relativeDir: String, entries: List<BrowseEntryRemote>): Boolean {
        val key = normalizeDir(relativeDir)
        val file = listingFile(sourceDir, key)
        return writeListingFile(file, key, entries)
    }

    /**
     * Delete [relativeDir] and every nested listing (`dir/file.zip`, `dir/file.zip/Album`).
     * Empty [relativeDir] is ignored so a whole source is never wiped this way.
     */
    fun removeUnder(relativeDir: String) {
        val prefix = normalizeDir(relativeDir)
        if (prefix.isEmpty()) return
        listingFile(sourceDir, prefix).delete()
        val subtree = listingSubtree(sourceDir, prefix)
        if (subtree.isDirectory) subtree.deleteRecursively()
    }

    fun loadAllListings(): Map<String, List<BrowseEntryRemote>> {
        val listings = listingsDir(sourceDir)
        if (!listings.isDirectory) return emptyMap()
        val out = LinkedHashMap<String, List<BrowseEntryRemote>>()
        listings.walkTopDown().forEach { file ->
            if (!isListingJson(file)) return@forEach
            val parsed = parseListingFile(file) ?: return@forEach
            out[parsed.dir] = parsed.entries
        }
        return out
    }

    fun deleteSource() {
        if (sourceDir.isDirectory) sourceDir.deleteRecursively() else sourceDir.delete()
    }

    private data class ParsedListing(
        val dir: String,
        val entries: List<BrowseEntryRemote>,
    )

    companion object {
        const val LAYOUT_VERSION = 6
        const val LEGACY_BLOB_VERSION = 5

        private const val KIND_DIRECTORY = "directory"
        private const val KIND_FOLDER_GALLERY = "folder_gallery"
        private const val KIND_ARCHIVE = "archive"
        private const val KIND_VIDEO = "video"
        private const val KIND_FILE = "file"
        private const val MAX_SEGMENT_LEN = 200
        private const val ROOT_LISTING_NAME = "@.json"
        private const val LISTINGS_DIR = "d"
        private const val META_NAME = "meta.json"

        fun sourceDirName(protocol: String, sourceId: Long) = "${protocol}_$sourceId"

        fun legacyBlobName(protocol: String, sourceId: Long) = "${protocol}_$sourceId.json"

        fun metaFile(sourceDir: File) = File(sourceDir, META_NAME)

        fun listingsDir(sourceDir: File) = File(sourceDir, LISTINGS_DIR)

        fun listingFile(sourceDir: File, relativeDir: String): File {
            val d = listingsDir(sourceDir)
            val parts = listingSegments(relativeDir)
            if (parts.isEmpty()) return File(d, ROOT_LISTING_NAME)
            var dir = d
            for (i in 0 until parts.lastIndex) {
                dir = File(dir, encodeSegment(parts[i]))
            }
            return File(dir, "${encodeSegment(parts.last())}.json")
        }

        fun listingSubtree(sourceDir: File, relativeDir: String): File {
            val d = listingsDir(sourceDir)
            val parts = listingSegments(relativeDir)
            var dir = d
            for (part in parts) {
                dir = File(dir, encodeSegment(part))
            }
            return dir
        }

        fun encodeSegment(segment: String): String {
            if (segment == ".") return "%2E"
            if (segment == "..") return "%2E%2E"
            val encoded = buildString(segment.length) {
                for (c in segment) {
                    if (isSafeSegmentChar(c)) {
                        append(c)
                    } else {
                        append('%')
                        append(c.code.toString(16).padStart(2, '0').uppercase())
                    }
                }
            }
            if (encoded.isEmpty()) return "%00"
            if (encoded == "@") return "%40"
            if (encoded.length <= MAX_SEGMENT_LEN) return encoded
            val digest = sha256Hex(segment).take(16)
            return encoded.take(MAX_SEGMENT_LEN - 17) + "~" + digest
        }

        /**
         * Split a v5 `{version,configKey,folders}` blob into per-folder files.
         * Existing listing files are kept (a save that raced migration wins).
         * [meta.json] is written last so a crash retries from the blob.
         */
        fun migrateFromV5Blob(blob: File, sourceDir: File): Boolean {
            val parsed = parseV5Blob(blob) ?: return false
            sourceDir.mkdirs()
            val disk = FolderIndexDisk(sourceDir)
            for ((dir, entries) in parsed.folders) {
                val dest = listingFile(sourceDir, dir)
                if (dest.isFile && dest.length() > 0L) continue
                if (!disk.writeListing(dir, entries)) {
                    logcat("FolderIndex") { "Migrate listing failed dir=$dir" }
                    return false
                }
            }
            if (!disk.writeMeta(parsed.configKey)) return false
            return disk.readMeta() != null
        }

        fun parseV5Blob(blob: File): V5Blob? {
            val root = readJsonObject(blob) ?: return null
            if (root.optInt("version", -1) != LEGACY_BLOB_VERSION) return null
            val folders = root.optJSONObject("folders") ?: return V5Blob(root.optString("configKey"), emptyMap())
            val out = LinkedHashMap<String, List<BrowseEntryRemote>>()
            val keys = folders.keys()
            while (keys.hasNext()) {
                val keyName = keys.next()
                val array = folders.optJSONArray(keyName) ?: continue
                val decoded = runCatching { decodeEntries(array) }
                    .onFailure { logcat("FolderIndex", it) }
                    .getOrNull()
                    ?: continue
                out[normalizeDir(keyName)] = decoded
            }
            return V5Blob(root.optString("configKey"), out)
        }

        data class V5Blob(
            val configKey: String,
            val folders: Map<String, List<BrowseEntryRemote>>,
        )

        internal fun encodeEntries(entries: List<BrowseEntryRemote>) = JSONArray().apply {
            entries.forEach { put(encodeEntry(it)) }
        }

        internal fun encodeEntry(entry: BrowseEntryRemote) = JSONObject().apply {
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
                    if (entry.lastModifiedMs > 0L) put("lastModifiedMs", entry.lastModifiedMs)
                    if (entry.size > 0L) put("size", entry.size)
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
        }

        internal fun decodeEntries(array: JSONArray): List<BrowseEntryRemote> = buildList(array.length()) {
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
                            lastModifiedMs = item.optLong("lastModifiedMs"),
                            size = item.optLong("size"),
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

        internal fun normalizeDir(relativeDir: String) = relativeDir.replace('\\', '/').trim('/')

        private fun listingSegments(relativeDir: String): List<String> {
            val norm = normalizeDir(relativeDir)
            if (norm.isEmpty()) return emptyList()
            return norm.split('/').filter { it.isNotEmpty() }
        }

        private fun isSafeSegmentChar(c: Char): Boolean = c == '.' ||
            c == '-' ||
            c == '_' ||
            c in '0'..'9' ||
            c in 'a'..'z' ||
            c in 'A'..'Z'

        private fun isListingJson(file: File): Boolean {
            if (!file.isFile) return false
            val name = file.name
            if (!name.endsWith(".json")) return false
            if (name.contains(".tmp.")) return false
            return true
        }

        private fun parseListingFile(file: File): ParsedListing? {
            val root = readJsonObject(file) ?: return null
            if (root.optInt("version", -1) != LAYOUT_VERSION) return null
            val array = root.optJSONArray("entries") ?: return null
            val dir = normalizeDir(root.optString("dir"))
            val entries = runCatching { decodeEntries(array) }
                .onFailure { logcat("FolderIndex", it) }
                .getOrNull()
                ?: return null
            return ParsedListing(dir, entries)
        }

        private fun readJsonObject(file: File): JSONObject? {
            if (!file.isFile || file.length() <= 0L) return null
            return runCatching { JSONObject(file.readText()) }
                .onFailure { logcat("FolderIndex", it) }
                .getOrNull()
        }

        /**
         * Stream one listing so a 5 000-file folder does not build a single giant
         * [JSONObject.toString] in RAM (that OOM / cancel drops the cache).
         */
        private fun writeListingFile(
            file: File,
            dir: String,
            entries: List<BrowseEntryRemote>,
        ): Boolean = writeJsonBytes(file) { writer ->
            writer.append("{\"version\":").append(LAYOUT_VERSION.toString())
            writer.append(",\"dir\":").append(JSONObject.quote(dir))
            writer.append(",\"entries\":[")
            entries.forEachIndexed { i, entry ->
                if (i > 0) writer.append(',')
                writer.append(encodeEntry(entry).toString())
            }
            writer.append("]}")
        }

        private fun writeJsonFile(file: File, root: JSONObject): Boolean = writeJsonBytes(file) { it.append(root.toString()) }

        private fun writeJsonBytes(file: File, write: (Appendable) -> Unit): Boolean {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
            return try {
                tmp.bufferedWriter().use { write(it) }
                if (CachePagePublish.atomicReplaceFile(tmp, file)) {
                    file.setLastModified(System.currentTimeMillis())
                    true
                } else {
                    false
                }
            } catch (e: Throwable) {
                logcat("FolderIndex", e)
                false
            } finally {
                tmp.delete()
            }
        }

        private fun sha256Hex(segment: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(segment.toByteArray())
            return buildString(bytes.size * 2) {
                for (b in bytes) {
                    append((b.toInt() and 0xff).toString(16).padStart(2, '0'))
                }
            }
        }

        private fun JSONObject.optNullableString(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return List(length()) { getString(it) }
        }
    }
}
