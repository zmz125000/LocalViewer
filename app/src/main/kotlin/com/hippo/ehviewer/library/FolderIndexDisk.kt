package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-disk layout for one source's folder listings.
 *
 * `{sourceDir}/meta.json` — layout version + configKey stamp
 * `{sourceDir}/d/@.json` — listing for the source root (`relativeDir == ""`)
 * `{sourceDir}/d/{encoded}/.../{encoded}.json` — one listing per relativeDir
 *
 * Saving a folder writes only that listing file. Sibling folders are not rewritten
 * or re-parsed. Legacy v5 `{protocol}_{id}.json` blobs are split by [migrateFromV5Blob].
 *
 * Current-dir gallery images are stored once ([BrowseEntryRemote.FolderGallery.imageFileNames]);
 * matching [BrowseEntryRemote.RegularFile] rows are rebuilt on load.
 */
internal class FolderIndexDisk(val sourceDir: File) {
    data class Meta(val version: Int, val configKey: String)

    fun readMeta(): Meta? {
        val file = metaFile(sourceDir)
        val parsed = decodeFile<FolderIndexMetaFile>(file) ?: return null
        if (parsed.version != LAYOUT_VERSION) return null
        return Meta(parsed.version, parsed.configKey)
    }

    fun writeMeta(configKey: String): Boolean {
        sourceDir.mkdirs()
        return encodeFile(metaFile(sourceDir), FolderIndexMetaFile(LAYOUT_VERSION, configKey))
    }

    fun readListing(relativeDir: String): List<BrowseEntryRemote>? {
        val file = listingFile(sourceDir, relativeDir)
        return parseListingFile(file)?.entries
    }

    fun writeListing(relativeDir: String, entries: List<BrowseEntryRemote>): Boolean {
        val key = normalizeDir(relativeDir)
        val file = listingFile(sourceDir, key)
        val dto = FolderIndexListingFile(
            version = LAYOUT_VERSION,
            dir = key,
            entries = compactListingEntries(entries).map(FolderIndexEntryFile::fromRemote),
        )
        return encodeFile(file, dto)
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

        private const val MAX_SEGMENT_LEN = 200
        private const val ROOT_LISTING_NAME = "@.json"
        private const val LISTINGS_DIR = "d"
        private const val META_NAME = "meta.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

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
            if (!blob.isFile || blob.length() <= 0L) return null
            val root = runCatching { json.parseToJsonElement(blob.readText()).jsonObject }
                .onFailure { logcat("FolderIndex", it) }
                .getOrNull() ?: return null
            if (root["version"]?.jsonPrimitive?.intOrNull != LEGACY_BLOB_VERSION) return null
            val configKey = root["configKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val foldersEl = root["folders"]?.jsonObject
                ?: return V5Blob(configKey, emptyMap())
            val out = LinkedHashMap<String, List<BrowseEntryRemote>>()
            for ((keyName, value) in foldersEl) {
                val array = runCatching { value.jsonArray }.getOrNull() ?: continue
                val decoded = runCatching {
                    json.decodeFromJsonElement<List<FolderIndexEntryFile>>(array)
                        .map { it.toRemote() }
                        .let(::expandListingEntries)
                }.onFailure { logcat("FolderIndex", it) }.getOrNull() ?: continue
                out[normalizeDir(keyName)] = decoded
            }
            return V5Blob(configKey, out)
        }

        data class V5Blob(
            val configKey: String,
            val folders: Map<String, List<BrowseEntryRemote>>,
        )

        /**
         * Drop current-dir image [BrowseEntryRemote.RegularFile] rows that are already
         * listed on the self [BrowseEntryRemote.FolderGallery.imageFileNames].
         */
        internal fun compactListingEntries(entries: List<BrowseEntryRemote>): List<BrowseEntryRemote> {
            val galleryNames = HashSet<String>()
            for (entry in entries) {
                if (entry !is BrowseEntryRemote.FolderGallery) continue
                if (normalizeRel(entry.relativeName).isNotEmpty()) continue
                if (entry.imageFileNames.isEmpty()) continue
                galleryNames.addAll(entry.imageFileNames)
            }
            if (galleryNames.isEmpty()) return entries
            return entries.filterNot { entry ->
                entry is BrowseEntryRemote.RegularFile &&
                    !entry.hidden &&
                    !entry.virtual &&
                    directChildName(entry.fileName) in galleryNames
            }
        }

        /**
         * Rebuild current-dir image file rows omitted by [compactListingEntries].
         * Existing RegularFile rows (legacy listings, hidden files) are kept.
         */
        internal fun expandListingEntries(entries: List<BrowseEntryRemote>): List<BrowseEntryRemote> {
            val existing = HashSet<String>()
            for (entry in entries) {
                if (entry !is BrowseEntryRemote.RegularFile) continue
                directChildName(entry.fileName)?.let { existing += it }
            }
            val added = ArrayList<BrowseEntryRemote.RegularFile>()
            for (entry in entries) {
                if (entry !is BrowseEntryRemote.FolderGallery) continue
                if (normalizeRel(entry.relativeName).isNotEmpty()) continue
                for (name in entry.imageFileNames) {
                    val leaf = directChildName(name) ?: continue
                    if (!existing.add(leaf)) continue
                    added += BrowseEntryRemote.RegularFile(name = leaf, fileName = leaf)
                }
            }
            if (added.isEmpty()) return entries
            return entries + added
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
            val parsed = decodeFile<FolderIndexListingFile>(file) ?: return null
            if (parsed.version != LAYOUT_VERSION) return null
            val entries = runCatching {
                expandListingEntries(parsed.entries.map { it.toRemote() })
            }.onFailure { logcat("FolderIndex", it) }.getOrNull() ?: return null
            return ParsedListing(normalizeDir(parsed.dir), entries)
        }

        @OptIn(ExperimentalSerializationApi::class)
        private inline fun <reified T> decodeFile(file: File): T? {
            if (!file.isFile || file.length() <= 0L) return null
            return runCatching {
                file.inputStream().buffered().use { json.decodeFromStream<T>(it) }
            }.onFailure { logcat("FolderIndex", it) }.getOrNull()
        }

        @OptIn(ExperimentalSerializationApi::class)
        private inline fun <reified T> encodeFile(file: File, value: T): Boolean {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
            return try {
                tmp.outputStream().buffered().use { json.encodeToStream(value, it) }
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

        private fun normalizeRel(relativeName: String) = relativeName.replace('\\', '/').trim('/')

        private fun directChildName(relativeName: String): String? {
            val normalized = normalizeRel(relativeName)
            if (normalized.isEmpty() || '/' in normalized) return null
            return normalized
        }
    }
}

@Serializable
private data class FolderIndexMetaFile(
    val version: Int,
    val configKey: String = "",
)

@Serializable
private data class FolderIndexListingFile(
    val version: Int,
    val dir: String = "",
    val entries: List<FolderIndexEntryFile> = emptyList(),
)

@Serializable
private data class FolderIndexEntryFile(
    val kind: String,
    val name: String,
    val hidden: Boolean = false,
    val virtual: Boolean = false,
    val relativeName: String? = null,
    val hasVideo: Boolean = false,
    val hasGallery: Boolean = false,
    val hasDocument: Boolean = false,
    val presence: String? = null,
    val coverFileName: String? = null,
    val lastModifiedMs: Long = 0L,
    val size: Long = 0L,
    val unreachable: Boolean = false,
    val zipStale: Boolean = false,
    val pageCount: Int = 0,
    val pageCountCapped: Boolean = false,
    val imageFileNames: List<String> = emptyList(),
    val fileName: String? = null,
    val parentRelativeName: String? = null,
) {
    fun toRemote(): BrowseEntryRemote = when (kind) {
        KIND_DIRECTORY -> BrowseEntryRemote.Directory(
            name = name,
            relativeName = relativeName ?: name,
            hasVideo = hasVideo,
            hasGallery = hasGallery,
            hasDocument = hasDocument,
            presence = presence?.let { runCatching { DirPresence.valueOf(it) }.getOrNull() }
                ?: DirPresence.Navigable,
            coverFileName = coverFileName,
            lastModifiedMs = lastModifiedMs,
            size = size,
            hidden = hidden,
            virtual = virtual,
            unreachable = unreachable,
            zipStale = zipStale,
        )
        KIND_FOLDER_GALLERY -> BrowseEntryRemote.FolderGallery(
            name = name,
            relativeName = relativeName ?: "",
            pageCount = pageCount,
            pageCountCapped = pageCountCapped,
            coverFileName = coverFileName,
            imageFileNames = imageFileNames,
            lastModifiedMs = lastModifiedMs,
            size = size,
            hidden = hidden,
            virtual = virtual,
        )
        KIND_ARCHIVE -> BrowseEntryRemote.ArchiveGallery(
            name = name,
            fileName = fileName ?: name,
            parentRelativeName = parentRelativeName.orEmpty(),
            size = size,
            lastModifiedMs = lastModifiedMs,
            pageCount = pageCount,
            hidden = hidden,
            virtual = virtual,
        )
        KIND_VIDEO -> BrowseEntryRemote.VideoFile(
            name = name,
            fileName = fileName ?: name,
            size = size,
            lastModifiedMs = lastModifiedMs,
            hidden = hidden,
            virtual = virtual,
        )
        KIND_FILE -> BrowseEntryRemote.RegularFile(
            name = name,
            fileName = fileName ?: name,
            size = size,
            lastModifiedMs = lastModifiedMs,
            hidden = hidden,
            virtual = virtual,
        )
        else -> error("Unknown network folder index entry")
    }

    companion object {
        private const val KIND_DIRECTORY = "directory"
        private const val KIND_FOLDER_GALLERY = "folder_gallery"
        private const val KIND_ARCHIVE = "archive"
        private const val KIND_VIDEO = "video"
        private const val KIND_FILE = "file"

        fun fromRemote(entry: BrowseEntryRemote) = when (entry) {
            is BrowseEntryRemote.Directory -> FolderIndexEntryFile(
                kind = KIND_DIRECTORY,
                name = entry.name,
                hidden = entry.hidden,
                virtual = entry.virtual,
                relativeName = entry.relativeName,
                hasVideo = entry.hasVideo,
                hasGallery = entry.hasGallery,
                hasDocument = entry.hasDocument,
                presence = entry.presence.name,
                coverFileName = entry.coverFileName,
                lastModifiedMs = entry.lastModifiedMs,
                size = entry.size,
                unreachable = entry.unreachable,
                zipStale = entry.zipStale,
            )
            is BrowseEntryRemote.FolderGallery -> FolderIndexEntryFile(
                kind = KIND_FOLDER_GALLERY,
                name = entry.name,
                hidden = entry.hidden,
                virtual = entry.virtual,
                relativeName = entry.relativeName,
                coverFileName = entry.coverFileName,
                lastModifiedMs = entry.lastModifiedMs,
                size = entry.size,
                pageCount = entry.pageCount,
                pageCountCapped = entry.pageCountCapped,
                imageFileNames = entry.imageFileNames,
            )
            is BrowseEntryRemote.ArchiveGallery -> FolderIndexEntryFile(
                kind = KIND_ARCHIVE,
                name = entry.name,
                hidden = entry.hidden,
                virtual = entry.virtual,
                lastModifiedMs = entry.lastModifiedMs,
                size = entry.size,
                pageCount = entry.pageCount,
                fileName = entry.fileName,
                parentRelativeName = entry.parentRelativeName,
            )
            is BrowseEntryRemote.VideoFile -> FolderIndexEntryFile(
                kind = KIND_VIDEO,
                name = entry.name,
                hidden = entry.hidden,
                virtual = entry.virtual,
                lastModifiedMs = entry.lastModifiedMs,
                size = entry.size,
                fileName = entry.fileName,
            )
            is BrowseEntryRemote.RegularFile -> FolderIndexEntryFile(
                kind = KIND_FILE,
                name = entry.name,
                hidden = entry.hidden,
                virtual = entry.virtual,
                lastModifiedMs = entry.lastModifiedMs,
                size = entry.size,
                fileName = entry.fileName,
            )
        }
    }
}
