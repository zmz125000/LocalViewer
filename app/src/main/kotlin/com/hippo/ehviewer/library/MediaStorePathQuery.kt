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
        val root = relativeDir.replace('\\', '/').trim('/')
        if (root.isEmpty()) return null
        val selection =
            "$RELATIVE_PATH = ? OR $RELATIVE_PATH = ? OR $RELATIVE_PATH LIKE ?"
        return selection to arrayOf("$root/", root, "$root/%")
    }

    /** OEM rows that leave [RELATIVE_PATH] empty; pair with [DATA] in the projection. */
    fun emptyRelativePathSelection(): String = "$RELATIVE_PATH IS NULL OR $RELATIVE_PATH = '' OR $RELATIVE_PATH = '/'"

    fun resolveByRelativePathSelection(relativeDir: String, fileName: String): Pair<String, Array<String>> {
        val relWithSlash = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        return if (relativeDir.isEmpty()) {
            "(${emptyRelativePathSelection()}) AND $DISPLAY_NAME = ?" to arrayOf(fileName)
        } else {
            "($RELATIVE_PATH = ? OR $RELATIVE_PATH = ?) AND $DISPLAY_NAME = ?" to
                arrayOf(relWithSlash, relativeDir, fileName)
        }
    }

    /** Last-resort open lookup for OEM rows with empty [RELATIVE_PATH]. */
    fun resolveByDataSelection(relativeDir: String, fileName: String): Pair<String, Array<String>> {
        val like = if (relativeDir.isEmpty()) {
            "%/$fileName"
        } else {
            "%/$relativeDir/$fileName"
        }
        return "$DATA LIKE ? AND $DISPLAY_NAME = ?" to arrayOf(like, fileName)
    }
}
