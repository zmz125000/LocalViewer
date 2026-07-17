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
