package com.ehviewer.core.files

/**
 * Folder of a MediaStore row, relative to shared storage
 * (`Pictures/Comics`, not including the file name).
 *
 * [android.provider.MediaStore.MediaColumns.RELATIVE_PATH] is preferred
 * (trailing slash stripped). Some OEM video indexers leave that column empty
 * while still filling [android.provider.MediaStore.MediaColumns.DATA]; derive
 * the same folder from the absolute path so MediaStore browse matches SAF.
 */
fun mediaStoreParentRelativeDir(relativePath: String?, dataPath: String?): String {
    val fromRel = relativePath?.replace('\\', '/')?.trim()?.trim('/') ?: ""
    if (fromRel.isNotEmpty()) return fromRel
    val raw = dataPath?.replace('\\', '/')?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val full = raw.removePrefix("file://")
    val parent = full.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isEmpty()) return ""
    val prefixes = listOf(
        "/storage/emulated/0/",
        "/storage/self/primary/",
        "/sdcard/",
        "/mnt/sdcard/",
    )
    for (prefix in prefixes) {
        if (parent.startsWith(prefix, ignoreCase = true)) {
            return parent.substring(prefix.length).trim('/')
        }
    }
    // Secondary volume: `/storage/<uuid>/Movies` → `Movies`
    if (parent.startsWith("/storage/", ignoreCase = true)) {
        val rest = parent.substring("/storage/".length)
        val slash = rest.indexOf('/')
        if (slash > 0) {
            val volume = rest.substring(0, slash)
            if (!volume.equals("emulated", ignoreCase = true) &&
                !volume.equals("self", ignoreCase = true)
            ) {
                return rest.substring(slash + 1).trim('/')
            }
        }
    }
    return ""
}
