package com.hippo.ehviewer.library

/**
 * Stable 64-bit id so read progress/history survive rescans when path is unchanged.
 */
fun stableGalleryId(rootId: Long, relativePath: String): Long {
    val key = "$rootId\u0000$relativePath"
    var h = -0x340d631b7bdddcdbL // FNV offset basis
    for (c in key) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L // FNV prime
    }
    // Avoid 0 which is unused/invalid in some call sites
    return if (h == 0L) 1L else h
}

/** Library folder key `.` (scan) is browse relative `` (root). */
fun libraryBrowseRelative(relativePath: String): String = if (relativePath.isEmpty() || relativePath == ".") "" else relativePath

fun libraryVideoFolderId(rootId: Long, folderKey: String): Long = stableGalleryId(rootId, "v:$folderKey")

fun libraryVideoFileId(rootId: Long, fileRel: String): Long = stableGalleryId(rootId, "vf:$fileRel")
