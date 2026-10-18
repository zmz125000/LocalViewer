package com.ehviewer.core.files

/**
 * Virtual MediaStore path for one volume.
 *
 * Device media stays `mediastore:/Pictures/Comics`.
 * A single mounted volume (USB) is `mediastore:/@v/{volumeName}/Pictures/Comics`,
 * where [volumeName] is [android.provider.MediaStore] volume name
 * (`StorageVolume.getMediaStoreVolumeName()`).
 */
data class MediaStoreVirtualLoc(
    /** Null means [android.provider.MediaStore.VOLUME_EXTERNAL] (every volume). */
    val volumeName: String?,
    /** Folder or file path under that volume, no leading slash. */
    val relative: String,
)

fun parseMediaStoreVirtual(path: String): MediaStoreVirtualLoc? {
    if (!path.startsWith("mediastore:")) return null
    val rest = path.removePrefix("mediastore:").trim('/')
    if (!rest.startsWith(MEDIASTORE_VOLUME_MARKER)) {
        return MediaStoreVirtualLoc(volumeName = null, relative = rest)
    }
    val body = rest.removePrefix(MEDIASTORE_VOLUME_MARKER)
    val slash = body.indexOf('/')
    val volume = if (slash < 0) body else body.substring(0, slash)
    if (volume.isEmpty()) return MediaStoreVirtualLoc(volumeName = null, relative = "")
    val relative = if (slash < 0) "" else body.substring(slash + 1).trim('/')
    return MediaStoreVirtualLoc(volumeName = volume, relative = relative)
}

fun mediaStoreVirtualPath(volumeName: String?, relative: String): String {
    val rel = relative.trim('/')
    return if (volumeName.isNullOrEmpty()) {
        if (rel.isEmpty()) "mediastore:/" else "mediastore:/$rel"
    } else if (rel.isEmpty()) {
        "mediastore:/$MEDIASTORE_VOLUME_MARKER$volumeName"
    } else {
        "mediastore:/$MEDIASTORE_VOLUME_MARKER$volumeName/$rel"
    }
}

/** Root of one MediaStore volume (`StorageVolume.getMediaStoreVolumeName()`). */
fun usbMediaStoreRoot(volumeName: String): String = mediaStoreVirtualPath(volumeName, "")

private const val MEDIASTORE_VOLUME_MARKER = "@v/"
