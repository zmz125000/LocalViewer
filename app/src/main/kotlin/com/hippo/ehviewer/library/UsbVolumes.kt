package com.hippo.ehviewer.library

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.ehviewer.core.util.logcat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Removable volumes currently mounted. Identity is [StorageVolume.getUuid],
 * the same id as the SAF tree (`XXXX-XXXX:`).
 */
object UsbVolumes {
    private val _mounted = MutableStateFlow<List<UsbVolume>>(emptyList())
    val mounted = _mounted.asStateFlow()

    private val _openRequests = MutableSharedFlow<UsbVolume>(extraBufferCapacity = 4)
    val openRequests = _openRequests.asSharedFlow()

    private var callback: StorageManager.StorageVolumeCallback? = null

    fun start(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (callback != null) return
        val manager = app.getSystemService(StorageManager::class.java) ?: return
        val watch = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                refresh(app)
            }
        }
        manager.registerStorageVolumeCallback(app.mainExecutor, watch)
        callback = watch
    }

    fun requestOpen(volume: UsbVolume) {
        _openRequests.tryEmit(volume)
    }

    fun refresh(context: Context) {
        val next = runCatching { listMounted(context) }.getOrElse {
            logcat(it)
            emptyList()
        }
        _mounted.value = next
    }

    fun listMounted(context: Context): List<UsbVolume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume -> volume.toUsbVolume(context) }
    }

    private fun StorageVolume.toUsbVolume(context: Context): UsbVolume? {
        if (!isRemovable || isEmulated || isPrimary) return null
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
            return null
        }
        val id = uuid?.takeIf { it.isNotBlank() } ?: return null
        val label = getDescription(context)?.takeIf { it.isNotBlank() } ?: id
        return UsbVolume(uuid = id, label = label)
    }
}
