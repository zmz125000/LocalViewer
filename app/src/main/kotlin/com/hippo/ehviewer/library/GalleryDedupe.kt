package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LocalGalleryEntity
import okio.Path.Companion.toPath

/**
 * Hide (filter) duplicate library galleries without deleting DB rows.
 *
 * When the same folder is present as both MediaStore and SAF/file paths
 * (or two rows map to the same storage relative path), keep the MediaStore one.
 */
fun List<LocalGalleryEntity>.hideDuplicateGalleriesPreferMediaStore(): List<LocalGalleryEntity> {
    if (size <= 1) return this
    // Preserve first-seen order (list is already title-sorted from DAO).
    val winners = LinkedHashMap<String, LocalGalleryEntity>(size)
    for (g in this) {
        val key = galleryIdentityKey(g)
        val existing = winners[key]
        winners[key] = if (existing == null) g else preferredGallery(existing, g)
    }
    return winners.values.toList()
}

/**
 * Identity for dedupe: MediaStore-relative path when known (including SAF→MS map),
 * else unique content path so unrelated roots with the same folder name are kept.
 */
internal fun galleryIdentityKey(gallery: LocalGalleryEntity): String {
    val path = gallery.contentPath.toPath()
    val msRel = when {
        path.isMediaStorePath() -> path.mediaStoreRelativeDir()
        else -> tryConvertSafPathToMediaStore(path)?.mediaStoreRelativeDir()
    }
    if (msRel != null) {
        return "ms:${msRel.lowercase()}|${gallery.kind}"
    }
    return "path:${gallery.contentPath.lowercase()}|${gallery.kind}"
}

internal fun preferredGallery(a: LocalGalleryEntity, b: LocalGalleryEntity): LocalGalleryEntity {
    val aMs = a.contentPath.startsWith("mediastore:")
    val bMs = b.contentPath.startsWith("mediastore:")
    if (aMs != bMs) return if (aMs) a else b
    // Prefer richer page count when both same backend.
    if (a.pageCount != b.pageCount) return if (a.pageCount > b.pageCount) a else b
    // Stable: lower id wins.
    return if (a.id <= b.id) a else b
}
