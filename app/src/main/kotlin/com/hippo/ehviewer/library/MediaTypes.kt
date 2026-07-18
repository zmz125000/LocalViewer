package com.hippo.ehviewer.library

import com.hippo.ehviewer.util.FileUtils

val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
)

val ARCHIVE_EXTENSIONS = setOf(
    "zip", "cbz", "rar", "cbr", "7z",
)

fun isImageFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in IMAGE_EXTENSIONS
}

fun isArchiveFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in ARCHIVE_EXTENSIONS
}

/**
 * Turn a SAF tree document-id path segment into a human folder name.
 *
 * Okio [okio.Path.name] for a tree root is often the raw/URL-encoded document id,
 * e.g. `primary%3APictures` or `8254-36A8%3ADCIM`, not the display name.
 */
fun humanizePathName(raw: String): String {
    if (raw.isEmpty()) return raw
    val decoded = runCatching {
        java.net.URLDecoder.decode(raw, Charsets.UTF_8)
    }.getOrDefault(raw)
    // ExternalStorageProvider document ids: "primary:Pictures" or "UUID:DCIM/..."
    val afterVolume = if (':' in decoded && !decoded.contains('/')) {
        decoded.substringAfterLast(':')
    } else if (':' in decoded) {
        // "primary:Pictures/Album" → use path after volume
        decoded.substringAfter(':', missingDelimiterValue = decoded)
    } else {
        decoded
    }
    return afterVolume
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifEmpty { decoded }
}

/**
 * Natural-order comparison for file names (digit runs compared as integers).
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            while (i < a.length && a[i] == '0') i++
            while (j < b.length && b[j] == '0') j++
            var zi = i
            var zj = j
            while (zi < a.length && a[zi].isDigit()) zi++
            while (zj < b.length && b[zj].isDigit()) zj++
            val lenDiff = (zi - i) - (zj - j)
            if (lenDiff != 0) return lenDiff
            while (i < zi) {
                val d = a[i].compareTo(b[j])
                if (d != 0) return d
                i++
                j++
            }
        } else {
            val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (d != 0) return d
            i++
            j++
        }
    }
    return a.length - b.length
}
