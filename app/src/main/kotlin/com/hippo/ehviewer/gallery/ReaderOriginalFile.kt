package com.hippo.ehviewer.gallery

/** Immutable original-file identity, safe to use after a reader session closes. */
sealed interface ReaderOriginalFile {
    data class Smb(val sourceId: Long, val relativePath: String) : ReaderOriginalFile
    data class WebDav(val sourceId: Long, val relativePath: String) : ReaderOriginalFile
}
