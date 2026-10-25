package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.image.hdr.isRawStillExtension
import java.lang.ref.WeakReference

/** Immutable original-file identity, safe to use after a reader session closes. */
sealed interface ReaderOriginalFile {
    // A queued/completed task must not pin a large compressed image or reader.
    // Losing this optional hit is harmless: the original identity remains valid.
    val cachedOriginal: WeakReference<ByteArray>?

    data class Smb(
        val sourceId: Long,
        val relativePath: String,
        override val cachedOriginal: WeakReference<ByteArray>? = null,
    ) : ReaderOriginalFile

    data class WebDav(
        val sourceId: Long,
        val relativePath: String,
        override val cachedOriginal: WeakReference<ByteArray>? = null,
    ) : ReaderOriginalFile
}

/** RAM entries are original downloads, except for the reader's RAW JPEG previews. */
@PublishedApi
internal fun readerOriginalRamHit(bytes: ByteArray?, preview: Boolean, name: String): WeakReference<ByteArray>? {
    if (bytes == null || bytes.isEmpty() || preview) return null
    // The RAM bytes and preview marker live in separate concurrent collections.
    // Also reject JPEG bytes under a RAW name across publication/removal races.
    if (isRawStillExtension(name.substringAfterLast('.', "")) && bytes.size >= 2 &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
    ) {
        return null
    }
    return WeakReference(bytes)
}
