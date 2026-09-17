package com.hippo.ehviewer.image

/**
 * Filename / header heuristics for animated pages (GIF, animated WebP, APNG).
 *
 * Reader stills honor [eu.kanade.tachiyomi.ui.reader.setting.DecodeSizeType];
 * animated pages stay at file resolution — downsampling them does not stop
 * per-frame decode and only hurts quality.
 */
internal fun isAnimatedReaderExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e == "gif" || e == "webp" || e == "awebp" || e == "apng"
}

/** True when the reader should skip [DecodeSizeType] and decode at file resolution. */
internal fun readerShouldDecodeOriginal(
    forceOriginal: Boolean,
    looksHdr: Boolean,
    looksAnimated: Boolean,
): Boolean = forceOriginal || looksHdr || looksAnimated

/**
 * Cheap header sniff. GIF always; WebP only with the VP8X animation bit;
 * PNG only when an `acTL` chunk appears before `IDAT`.
 */
internal fun looksLikeAnimatedImageHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
    val n = length.coerceIn(0, bytes.size)
    return isGifHeader(bytes, n) || isAnimatedWebPHeader(bytes, n) || isApngHeader(bytes, n)
}

private fun isGifHeader(bytes: ByteArray, n: Int): Boolean {
    if (n < 6) return false
    if (!asciiEquals(bytes, 0, "GIF", n)) return false
    return asciiEquals(bytes, 3, "87a", n) || asciiEquals(bytes, 3, "89a", n)
}

private fun isAnimatedWebPHeader(bytes: ByteArray, n: Int): Boolean {
    // RIFF....WEBP VP8X  + flags at offset 20, bit 1 = animation (same as Coil).
    if (n < 21) return false
    if (!asciiEquals(bytes, 0, "RIFF", n)) return false
    if (!asciiEquals(bytes, 8, "WEBP", n)) return false
    if (!asciiEquals(bytes, 12, "VP8X", n)) return false
    return (bytes[20].toInt() and 0x02) != 0
}

private fun isApngHeader(bytes: ByteArray, n: Int): Boolean {
    if (n < 16) return false
    if (bytes[0] != 0x89.toByte() ||
        bytes[1] != 0x50.toByte() ||
        bytes[2] != 0x4E.toByte() ||
        bytes[3] != 0x47.toByte() ||
        bytes[4] != 0x0D.toByte() ||
        bytes[5] != 0x0A.toByte() ||
        bytes[6] != 0x1A.toByte() ||
        bytes[7] != 0x0A.toByte()
    ) {
        return false
    }
    var i = 8
    while (i + 8 <= n) {
        val len = ((bytes[i].toInt() and 0xff) shl 24) or
            ((bytes[i + 1].toInt() and 0xff) shl 16) or
            ((bytes[i + 2].toInt() and 0xff) shl 8) or
            (bytes[i + 3].toInt() and 0xff)
        if (len < 0) return false
        if (asciiEquals(bytes, i + 4, "acTL", n)) return true
        if (asciiEquals(bytes, i + 4, "IDAT", n) || asciiEquals(bytes, i + 4, "IEND", n)) return false
        val next = i + 12 + len
        if (next <= i) return false
        i = next
    }
    return false
}

private fun asciiEquals(bytes: ByteArray, offset: Int, text: String, length: Int): Boolean {
    if (offset < 0 || offset + text.length > length) return false
    for (i in text.indices) {
        if (bytes[offset + i] != text[i].code.toByte()) return false
    }
    return true
}
