package com.ehviewer.core.database.model

/** Minimal, unsorted projection used to identify the storage behind history records. */
data class LocalGalleryStoragePath(val id: Long, val contentPath: String)
