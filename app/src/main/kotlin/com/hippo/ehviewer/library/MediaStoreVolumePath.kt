package com.hippo.ehviewer.library

/** Shared path identity plus the app's removable-storage privacy policy. */
object MediaStoreVolumePath {
    const val PRIMARY = com.ehviewer.core.files.MediaStoreVolumePath.PRIMARY

    fun volume(relative: String): String? = com.ehviewer.core.files.MediaStoreVolumePath.volume(relative)
    fun local(relative: String): String = com.ehviewer.core.files.MediaStoreVolumePath.local(relative)
    fun parent(volume: String?, relative: String): String = com.ehviewer.core.files.MediaStoreVolumePath.parent(volume, relative)

    fun accepts(relative: String, volumes: List<UsbVolume>, acceptUsb: Boolean, acceptSd: Boolean): Boolean {
        val id = volume(relative) ?: return true
        val kind = volumes.firstOrNull { it.uuid.equals(id, ignoreCase = true) }?.kind
        return when (kind) {
            ExternalStorageKind.SdCard -> acceptSd
            ExternalStorageKind.UsbDrive -> acceptUsb
            null -> false // Detached or unidentified removable storage is private by default.
        }
    }
}
