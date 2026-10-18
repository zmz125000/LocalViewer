package com.hippo.ehviewer.library

import android.content.Context
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.ehviewer.core.files.mediaStoreVirtualPath
import com.ehviewer.core.util.logcat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath

/**
 * SD cards and USB sticks from the public storage APIs a file app uses:
 * [DocumentsContract] roots on `com.android.externalstorage.documents`, and
 * [StorageManager.getStorageVolumes]. No hidden mount APIs and no sysfs.
 *
 * Android does not give a normal app a way to eject those volumes, so
 * [UsbVolume.ejectSupported] stays false.
 */
data class UsbVolume(
    val fsUuid: String,
    /** Filesystem id (`StorageVolume.getUuid` / documents root id). */
    val identity: String,
    val deviceSerial: String?,
    val cacheSourceId: Long,
    val label: String,
    /** Mount directory, such as `/storage/ABCD-1234`. */
    val directory: String,
    /** [StorageVolume.getMediaStoreVolumeName], lowercased filesystem id. */
    val mediaStoreVolumeName: String?,
    val volumeId: String?,
    val ejectSupported: Boolean,
) {
    fun toIndexVolume() = UsbIndexVolume(fsUuid, directory, cacheSourceId)

    fun matchesBrowseFrame(rootId: Long, path: String): Boolean = UsbFolderIndex.matchesBrowseFrame(
        cacheSourceId,
        directory,
        fsUuid,
        rootId,
        path,
    )
}

data class UsbBrowseTarget(
    val path: Path,
    val preferMediaStore: Boolean,
)

object UsbVolumes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val present = HashSet<String>()
    private var baselineReady = false

    private val _volumes = MutableStateFlow<List<UsbVolume>>(emptyList())
    val volumes: StateFlow<List<UsbVolume>> = _volumes

    private val _inserts = MutableSharedFlow<UsbVolume>(extraBufferCapacity = 8)
    val inserts: SharedFlow<UsbVolume> = _inserts.asSharedFlow()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        NetworkFolderIndexCache.localSourceIdAlias = { rootId, configKey ->
            UsbFolderIndex.aliasSourceId(rootId, configKey, _volumes.value.map { it.toIndexVolume() })
        }
        scope.launch {
            refreshRequests.collectLatest {
                delay(200)
                publish(scan(app))
            }
        }
        val storage = app.getSystemService(StorageManager::class.java) ?: return
        storage.registerStorageVolumeCallback(
            ContextCompat.getMainExecutor(app),
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    refreshRequests.tryEmit(Unit)
                }
            },
        )
        refreshRequests.tryEmit(Unit)
    }

    private fun publish(next: List<UsbVolume>) {
        logcat("UsbVolumes") { "side volumes: ${next.joinToString { it.identity }.ifEmpty { "(none)" }}" }
        if (baselineReady) {
            for (volume in next) {
                if (volume.identity !in present) {
                    _inserts.tryEmit(volume)
                }
            }
        }
        baselineReady = true
        present.clear()
        present.addAll(next.map { it.identity })
        _volumes.value = next
    }
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

private data class PublicRoot(val uuid: String, val title: String)

private fun scan(context: Context): List<UsbVolume> = runCatching {
    val storage = context.getSystemService(StorageManager::class.java)
    val byUuid = storage?.storageVolumes.orEmpty().mapNotNull { volume ->
        val uuid = volume.uuid?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        uuid.lowercase() to volume
    }.toMap()
    val fromRoots = queryExternalRoots(context).map { root ->
        root.toVolume(context, byUuid[root.uuid.lowercase()])
    }
    val seen = fromRoots.map { it.identity }.toMutableSet()
    val fromVolumes = storage?.storageVolumes.orEmpty().mapNotNull { volume ->
        if (!isSideStorageVolume(volume.isPrimary, volume.isEmulated, volume.state.orEmpty(), volume.uuid)) {
            return@mapNotNull null
        }
        val uuid = volume.uuid?.trim().orEmpty()
        val volumeEntry = PublicRoot(uuid, volume.getDescription(context).orEmpty()).toVolume(context, volume)
        if (volumeEntry.identity in seen) return@mapNotNull null
        volumeEntry
    }
    (fromRoots + fromVolumes).distinctBy { it.identity }.sortedBy { it.label.lowercase() }
}.getOrElse {
    logcat("UsbVolumes", it)
    emptyList()
}

/**
 * Same root list the system Files app and Solid Explorer read.
 * Skips internal `primary` and `home`.
 */
private fun queryExternalRoots(context: Context): List<PublicRoot> = runCatching {
    val uri = DocumentsContract.buildRootsUri(EXTERNAL_STORAGE_AUTHORITY)
    val projection = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
    )
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
        val titleIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
        if (idIdx < 0) return emptyList()
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx)?.trim().orEmpty()
                if (!isExternalSideRoot(id)) continue
                val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else ""
                add(PublicRoot(id, title))
            }
        }
    }.orEmpty()
}.getOrElse {
    logcat("UsbVolumes", it)
    emptyList()
}

private fun PublicRoot.toVolume(context: Context, volume: StorageVolume?): UsbVolume {
    val uuid = uuid.trim()
    val directory = sideStorageDirectory(uuid, volume?.directory?.path)
    val identity = UsbFolderIndex.folderIndexIdentity(uuid, deviceSerial = null) ?: uuid.lowercase()
    val label = title.trim().takeIf { it.isNotEmpty() }
        ?: volume?.getDescription(context)?.trim()?.takeIf { it.isNotEmpty() }
        ?: uuid
    return UsbVolume(
        fsUuid = uuid,
        identity = identity,
        deviceSerial = null,
        cacheSourceId = UsbFolderIndex.cacheSourceId(identity),
        label = label,
        directory = directory,
        mediaStoreVolumeName = volume?.mediaStoreVolumeName ?: uuid.lowercase(),
        volumeId = null,
        ejectSupported = false,
    )
}

/** Physical directory when the process can list it; otherwise that volume's MediaStore root. */
fun UsbVolume.browseTarget(): UsbBrowseTarget {
    val dir = File(directory)
    val listable = runCatching { dir.isDirectory && dir.list() != null }.getOrDefault(false)
    if (listable) return UsbBrowseTarget(directory.toPath(), preferMediaStore = false)
    val volumeName = mediaStoreVolumeName?.takeIf { it.isNotBlank() }
    if (volumeName != null) {
        return UsbBrowseTarget(mediaStoreVirtualPath(volumeName, "").toPath(), preferMediaStore = true)
    }
    return UsbBrowseTarget(directory.toPath(), preferMediaStore = false)
}
