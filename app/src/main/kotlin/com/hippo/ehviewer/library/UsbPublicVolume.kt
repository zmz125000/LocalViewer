package com.hippo.ehviewer.library

/**
 * Which public storage entries a file app lists beside internal storage.
 * [android.os.storage.StorageVolume] and the external-storage documents
 * provider both use these ids. `primary` is internal shared storage.
 * `home` is the system Downloads root. Anything else is an SD card or USB stick.
 */
fun isExternalSideRoot(rootId: String): Boolean {
    val id = rootId.trim()
    if (id.isEmpty()) return false
    if (id.equals("primary", ignoreCase = true)) return false
    if (id.equals("home", ignoreCase = true)) return false
    return true
}

/**
 * Extra mounted volume from the public [android.os.storage.StorageVolume] fields.
 * Not primary and not emulated: the same volumes a file app shows under USB / SD.
 * [isRemovable] is not required; some devices leave it false for a USB stick.
 */
fun isSideStorageVolume(primary: Boolean, emulated: Boolean, state: String, uuid: String?): Boolean {
    if (primary || emulated) return false
    if (uuid.isNullOrBlank()) return false
    return state == STATE_MOUNTED || state == STATE_MOUNTED_READ_ONLY
}

/** Mount path file apps use when the volume only reports its filesystem id. */
fun sideStorageDirectory(uuid: String, reportedPath: String?): String {
    val path = reportedPath?.trim()?.trimEnd('/')
    if (!path.isNullOrEmpty()) return path
    return "/storage/${uuid.trim()}"
}

internal const val STATE_MOUNTED = "mounted"
internal const val STATE_MOUNTED_READ_ONLY = "mounted_ro"
