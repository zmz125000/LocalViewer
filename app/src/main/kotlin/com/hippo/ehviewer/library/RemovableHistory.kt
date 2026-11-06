package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** History recording policy and display availability for removable storage. */
object RemovableHistory {
    /** Apply storage privacy before metadata, timestamps or parent-directory pins are written. */
    suspend fun blocksWrite(info: GalleryInfo): Boolean {
        val saveUsb = Settings.saveUsbHistory.value
        val saveSd = Settings.saveSdCardHistory.value
        if (saveUsb && saveSd) return false
        if (info.token !in localHistoryTokens) return false
        val path = storagePath(info)
            // Only missing identity needs the previous row; known internal paths need no fallback query.
            ?: EhDB.loadGalleryInfo(info.gid)?.let { storagePath(it) }
        val volume = path?.let(::removableVolumeInHistoryPath)
        return !removableHistoryWriteAllowed(volume, UsbVolumes.mounted.value, saveUsb, saveSd)
    }

    private suspend fun storagePath(info: GalleryInfo): String? {
        val target = LocalHistory.parse(info)
        val rootId = when (target) {
            is LocalHistoryTarget.LocalBrowseFolder -> target.rootId
            is LocalHistoryTarget.LocalFolderGallery -> target.rootId
            else -> null
        }
        val roots = rootId?.let { id -> LocalLibrary.loadRoot(id)?.let { mapOf(id to it.treeUri) } }.orEmpty()
        val paths = if (target is LocalHistoryTarget.LibraryGallery) {
            LocalLibrary.loadGallery(target.galleryId)?.let { mapOf(target.galleryId to it.contentPath) }.orEmpty()
        } else {
            emptyMap()
        }
        return historyStoragePath(info, roots, paths)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One subscription/index for History and Library Recent. Room opens only when collected.
    private val visibleRows by lazy {
        val roots = LocalLibrary.rootsFlow()
            .map { rows -> rows.associate { it.id to it.treeUri } }
            .distinctUntilChanged()
        val paths = LocalLibrary.galleryStoragePathsFlow()
            .map { rows -> rows.associate { it.id to it.contentPath } }
            .distinctUntilChanged()
        val indexes = flow {
            // Bounded to current history rows and released when the shared upstream stops.
            val indexer = HistoryStorageIndexer()
            emitAll(
                combine(EhDB.historyListFlow, roots, paths) { rows, rootUris, libraryPaths ->
                    indexer.build(rows, rootUris, libraryPaths)
                },
            )
        }
        val mountedIds = UsbVolumes.mounted
            .map { volumes -> volumes.map { it.uuid.lowercase() }.toSet() }
            .distinctUntilChanged()
        combine(indexes, mountedIds) { index, mounted -> index.connectedRows(mounted) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), replay = 1)
    }

    fun visibleRowsFlow(): Flow<List<GalleryEntity>> = visibleRows
}

private val localHistoryTokens = setOf(LOCAL_GALLERY_TOKEN, LOCAL_BROWSE_TOKEN, LOCAL_FOLDER_TOKEN, LOCAL_FILE_TOKEN, LOCAL_ARCHIVE_TOKEN)

internal fun removableHistoryWriteAllowed(volume: String?, volumes: List<UsbVolume>, saveUsb: Boolean, saveSd: Boolean): Boolean {
    if (volume == null) return true
    return when (volumes.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.kind) {
        ExternalStorageKind.UsbDrive -> saveUsb
        ExternalStorageKind.SdCard -> saveSd
        null -> saveUsb && saveSd // A disconnected or unidentified volume must satisfy both gates.
    }
}

internal fun <T : GalleryInfo> connectedHistoryRows(
    rows: List<T>,
    mountedVolumeIds: Set<String>,
    rootUris: Map<Long, String>,
    libraryPaths: Map<Long, String>,
): List<T> {
    val index = HistoryStorageIndex(rows) { info ->
        historyStoragePath(info, rootUris, libraryPaths)?.let(::removableVolumeInHistoryPath)
    }
    return index.connectedRows(mountedVolumeIds)
}

/** Reuse record identity across timestamp/reordering changes; discard removed records. */
internal class HistoryStorageIndexer(
    private val volumeOf: (GalleryInfo, Map<Long, String>, Map<Long, String>) -> String? = { info, roots, paths ->
        historyStoragePath(info, roots, paths)?.let(::removableVolumeInHistoryPath)
    },
) {
    private var rootsRef: Map<Long, String>? = null
    private var pathsRef: Map<Long, String>? = null
    private var previous = emptyMap<Long, Entry>()

    fun <T : GalleryInfo> build(rows: List<T>, roots: Map<Long, String>, paths: Map<Long, String>): HistoryStorageIndex<T> {
        if (roots !== rootsRef || paths !== pathsRef) previous = emptyMap()
        rootsRef = roots
        pathsRef = paths
        val next = HashMap<Long, Entry>(rows.size)
        val index = HistoryStorageIndex(rows) { info ->
            val cached = previous[info.gid]?.takeIf {
                it.token == info.token && it.uploader == info.uploader && it.thumbKey == info.thumbKey
            } ?: Entry(info.token, info.uploader, info.thumbKey, volumeOf(info, roots, paths))
            next[info.gid] = cached
            cached.volume
        }
        previous = next
        return index
    }

    private data class Entry(val token: String?, val uploader: String?, val thumbKey: String?, val volume: String?)
}

/** Parse once when history/source identity changes; mount updates perform only cached set lookups. */
internal class HistoryStorageIndex<T>(private val rows: List<T>, volumeOf: (T) -> String?) {
    private val volumes = rows.map { volumeOf(it)?.lowercase() }
    private val requiredVolumes = volumes.filterNotNull().toSet()

    fun connectedRows(mountedVolumeIds: Set<String>): List<T> {
        val mounted = mountedVolumeIds.map { it.lowercase() }.toSet()
        if (requiredVolumes.all { it in mounted }) return rows
        return rows.filterIndexed { index, _ -> volumes[index]?.let { it in mounted } != false }
    }
}

private fun historyStoragePath(info: GalleryInfo, rootUris: Map<Long, String>, libraryPaths: Map<Long, String>): String? {
    if (info.token !in localHistoryTokens) return null
    fun folderPath(rootId: Long, relative: String): String? = rootUris[rootId]?.let { root ->
        "${root.trimEnd('/')}/${relative.trim('/')}"
    }
    val path = when (val target = LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalBrowseFolder -> folderPath(target.rootId, target.relativePath)
        is LocalHistoryTarget.LocalFolderGallery -> folderPath(target.rootId, target.relativePath)
        is LocalHistoryTarget.LocalFile -> target.path
        is LocalHistoryTarget.LocalArchive -> target.path
        is LocalHistoryTarget.LibraryGallery -> libraryPaths[target.galleryId]
        is LocalHistoryTarget.Orphan -> null
        else -> return null // Network and online history do not depend on a local storage mount.
    }
    // A scanned gallery can disappear from the index on unplug; its stored cover retains identity.
    return path ?: info.thumbKey
}

internal fun removableVolumeInHistoryPath(raw: String): String? {
    val path = raw.removePrefix("vid-local:").let { ZipPaths.parse(it)?.first ?: it }
    if (path.startsWith("mediastore:")) {
        val relative = path.removePrefix("mediastore://external").removePrefix("mediastore:").trim('/')
        return MediaStoreVolumePath.volume(relative)
    }
    val normalized = if (path.startsWith("content://")) path else path.replaceFirst("content:/", "content://")
    if (normalized.startsWith("content://")) {
        // Stored Okio SAF children may contain raw spaces; parse identity before URI encoding.
        val content = normalized.removePrefix("content://")
        val authority = content.substringBefore('/')
        val uriPath = content.substringAfter('/', "")
        return when (authority) {
            "com.android.externalstorage.documents" -> {
                val id = safTreeDocumentId(normalized) ?: runCatching {
                    URLDecoder.decode(uriPath.substringAfter("document/", "").substringBefore('/').replace("+", "%2B"), "UTF-8")
                }.getOrNull() ?: return null
                externalStorageVolumeId(id)
            }
            "media" -> uriPath.substringBefore('/').takeUnless {
                it.isEmpty() || it == "external" || it == "internal" || it == MediaStoreVolumePath.PRIMARY
            }
            "mediastore" -> MediaStoreVolumePath.volume(uriPath)
            else -> null
        }
    }
    val physical = path.removePrefix("file://")
    val prefix = listOf("/storage/", "/mnt/media_rw/").firstOrNull { physical.startsWith(it) } ?: return null
    return physical.removePrefix(prefix).substringBefore('/').takeUnless {
        it.isEmpty() || it in setOf("emulated", "self", "primary", "sdcard0")
    }
}
