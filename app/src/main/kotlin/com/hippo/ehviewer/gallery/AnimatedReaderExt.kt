package com.hippo.ehviewer.gallery

internal fun isAnimatedReaderExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e == "gif" || e == "webp" || e == "awebp" || e == "apng"
}
