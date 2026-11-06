package com.hippo.ehviewer.library

/** Stable virtual directory per physical volume; labels may change between mounts. */
object MediaStoreVolumePath {
    private const val PREFIX = "@volume-"
    const val PRIMARY = "external_primary"

    fun volume(relative: String): String? = relative.trim('/').substringBefore('/')
        .takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }

    fun local(relative: String): String = if (volume(relative) == null) {
        relative.trim('/')
    } else {
        relative.trim('/').substringAfter('/', "")
    }

    fun parent(volume: String?, relative: String): String {
        val rel = relative.trim('/')
        if (volume.isNullOrBlank() || volume == PRIMARY) return rel
        val root = "$PREFIX${volume.lowercase()}"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

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
