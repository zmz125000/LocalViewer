package com.hippo.ehviewer.library

/** Shared path identity for physical MediaStore volumes. */
object MediaStoreVolumePath {
    const val PRIMARY = com.ehviewer.core.files.MediaStoreVolumePath.PRIMARY

    fun volume(relative: String): String? = com.ehviewer.core.files.MediaStoreVolumePath.volume(relative)
    fun local(relative: String): String = com.ehviewer.core.files.MediaStoreVolumePath.local(relative)
    fun parent(volume: String?, relative: String): String = com.ehviewer.core.files.MediaStoreVolumePath.parent(volume, relative)
}
