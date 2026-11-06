package com.hippo.ehviewer.library

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
