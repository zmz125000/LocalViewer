package com.ehviewer.core.files

/**
 * MediaStore listing filters that avoid unindexed `DATA LIKE '%…%'` table scans.
 * Column names match [android.provider.MediaStore.MediaColumns].
 */
object MediaStorePathQuery {
    const val ID = "_id"
    const val DISPLAY_NAME = "_display_name"
    const val RELATIVE_PATH = "relative_path"
    const val DATA = "_data"
    const val DATE_MODIFIED = "date_modified"

    /** Resolve a virtual file using its physical volume, then an OEM empty-path fallback. */
    fun <T> resolveFile(path: String, query: (volume: String, selection: String, args: Array<String>) -> T?): T? {
        if (!path.startsWith("mediastore:")) return null
        val filePath = path.removePrefix("mediastore:").trimStart('/')
        if (filePath.isEmpty() || filePath.endsWith('/')) return null
        // A removable volume root is a directory, not a primary-volume file name.
        if ('/' !in filePath && MediaStoreVolumePath.volume(filePath) != null) return null
        val fileName = filePath.substringAfterLast('/')
        val relativeDir = filePath.substringBeforeLast('/', "")
        val volume = MediaStoreVolumePath.volume(relativeDir) ?: MediaStoreVolumePath.PRIMARY
        val filters = listOf(
            resolveByRelativePathSelection(relativeDir, fileName),
            resolveByDataSelection(relativeDir, fileName),
        )
        for ((selection, args) in filters) {
            query(volume, selection, args)?.let { return it }
        }
        return null
    }

    /**
     * Prefix match on [RELATIVE_PATH] only. Null [Pair] means no SQL filter
     * (whole Images table — device media root).
     */
    fun descendantRelativePathSelection(relativeDir: String): Pair<String, Array<String>>? {
        val virtualRoot = relativeDir.replace('\\', '/').trim('/')
        if (virtualRoot.isEmpty()) return null
        val root = MediaStoreVolumePath.local(virtualRoot)
        val volume = MediaStoreVolumePath.volume(virtualRoot) ?: MediaStoreVolumePath.PRIMARY
        if (root.isEmpty()) return "volume_name = ?" to arrayOf(volume)
        val selection =
            "$RELATIVE_PATH = ? OR $RELATIVE_PATH = ? OR $RELATIVE_PATH LIKE ?"
        return "($selection) AND volume_name = ?" to arrayOf("$root/", root, "$root/%", volume)
    }

    /** Library scans use a concrete volume URI, so only the relative path needs filtering. */
    fun libraryDescendantSelection(relativeDir: String): Pair<String, Array<String>>? {
        val root = MediaStoreVolumePath.local(relativeDir.replace('\\', '/').trim('/'))
        if (root.isEmpty()) return null
        val selection = "$RELATIVE_PATH = ? OR $RELATIVE_PATH = ? OR $RELATIVE_PATH LIKE ?"
        return selection to arrayOf("$root/", root, "$root/%")
    }

    /** OEM rows that leave [RELATIVE_PATH] empty; pair with [DATA] in the projection. */
    fun emptyRelativePathSelection(): String = "$RELATIVE_PATH IS NULL OR $RELATIVE_PATH = '' OR $RELATIVE_PATH = '/'"

    fun resolveByRelativePathSelection(relativeDir: String, fileName: String): Pair<String, Array<String>> {
        val volume = MediaStoreVolumePath.volume(relativeDir) ?: MediaStoreVolumePath.PRIMARY
        val localDir = MediaStoreVolumePath.local(relativeDir)
        val relWithSlash = if (localDir.isEmpty()) "" else "$localDir/"
        return if (localDir.isEmpty()) {
            "(${emptyRelativePathSelection()}) AND $DISPLAY_NAME = ? AND volume_name = ?" to arrayOf(fileName, volume)
        } else {
            "($RELATIVE_PATH = ? OR $RELATIVE_PATH = ?) AND $DISPLAY_NAME = ? AND volume_name = ?" to
                arrayOf(relWithSlash, localDir, fileName, volume)
        }
    }

    /** Last-resort open lookup for OEM rows with empty [RELATIVE_PATH]. */
    fun resolveByDataSelection(relativeDir: String, fileName: String): Pair<String, Array<String>> {
        val volume = MediaStoreVolumePath.volume(relativeDir) ?: MediaStoreVolumePath.PRIMARY
        val localDir = MediaStoreVolumePath.local(relativeDir)
        val like = if (localDir.isEmpty()) {
            "%/$fileName"
        } else {
            "%/$localDir/$fileName"
        }
        return "$DATA LIKE ? AND $DISPLAY_NAME = ? AND volume_name = ?" to arrayOf(like, fileName, volume)
    }
}
