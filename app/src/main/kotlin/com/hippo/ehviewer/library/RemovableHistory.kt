package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** History recording policy and display availability for removable storage. */
object RemovableHistory {
    /** Apply storage privacy before metadata, timestamps or parent-directory pins are written. */
    suspend fun blocksWrite(info: GalleryInfo): Boolean {
        val saveUsb = Settings.saveUsbHistory.value
        val saveSd = Settings.saveSdCardHistory.value
        if (saveUsb && saveSd) return false
        if (info.token !in localHistoryTokens) return false
        val volume = storageVolume(info)
            // Sparse progress updates can omit a path that the previous history row retained.
            ?: EhDB.loadGalleryInfo(info.gid)?.let { storageVolume(it) }
        return !removableHistoryWriteAllowed(volume, UsbVolumes.mounted.value, saveUsb, saveSd)
    }

    private suspend fun storageVolume(info: GalleryInfo): String? {
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
        return historyStorageVolume(info, roots, paths)
    }

    fun visibleRowsFlow(): Flow<List<GalleryEntity>> = combine(
        EhDB.historyListFlow,
        UsbVolumes.mounted,
        LocalLibrary.rootsFlow(),
        LocalLibrary.galleriesFlow(),
    ) { rows, volumes, roots, galleries ->
        withContext(Dispatchers.Default) {
            connectedHistoryRows(
                rows,
                volumes.map { it.uuid }.toSet(),
                roots.associate { it.id to it.treeUri },
                galleries.associate { it.id to it.contentPath },
            )
        }
    }
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
    val mounted = mountedVolumeIds.map { it.lowercase() }.toSet()
    return rows.filter { info ->
        val volume = historyStorageVolume(info, rootUris, libraryPaths)
        volume == null || volume.lowercase() in mounted
    }
}

private fun historyStorageVolume(info: GalleryInfo, rootUris: Map<Long, String>, libraryPaths: Map<Long, String>): String? {
    fun folderPath(rootId: Long, relative: String): String? = rootUris[rootId]?.let { root ->
        "${root.trimEnd('/')}/${relative.trim('/')}"
    }
    val path = when (val target = LocalHistory.parse(info)) {
        is LocalHistoryTarget.LocalBrowseFolder -> folderPath(target.rootId, target.relativePath)
        is LocalHistoryTarget.LocalFolderGallery -> folderPath(target.rootId, target.relativePath)
        is LocalHistoryTarget.LocalFile -> target.path
        is LocalHistoryTarget.LocalArchive -> target.path
        is LocalHistoryTarget.LibraryGallery -> libraryPaths[target.galleryId]
        is LocalHistoryTarget.Orphan -> {
            if (info.token !in localHistoryTokens) return null
            null
        }
        else -> return null // Network and online history do not depend on a local storage mount.
    }
    // A scanned gallery can disappear from the index on unplug; its stored cover retains identity.
    return removableVolumeInHistoryPath(path ?: info.thumbKey ?: return null)
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
