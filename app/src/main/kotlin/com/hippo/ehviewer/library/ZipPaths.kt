package com.hippo.ehviewer.library

import okio.Path
import okio.Path.Companion.toPath

/**
 * Synthetic path encoding for ZIP/CBZ members in local browse [BrowseEntry] rows.
 * Not a real filesystem path — open/extract must [parse] first.
 *
 * Format: `zipfile:{absoluteZipPath}!{memberRel}`
 */
object ZipPaths {
    const val SCHEME = "zipfile:"
    private const val SEP = '!'

    fun encode(zipAbsolutePath: String, memberRel: String): String {
        val member = memberRel.replace('\\', '/').trimStart('/')
        return "$SCHEME$zipAbsolutePath$SEP$member"
    }

    fun encodePath(zipAbsolutePath: String, memberRel: String): Path = encode(zipAbsolutePath, memberRel).toPath()

    fun parse(path: String): Pair<String, String>? {
        if (!path.startsWith(SCHEME)) return null
        val rest = path.removePrefix(SCHEME)
        val sep = rest.indexOf(SEP)
        if (sep <= 0) return null
        val zip = rest.substring(0, sep)
        val member = rest.substring(sep + 1).replace('\\', '/').trimStart('/')
        if (zip.isEmpty() || member.isEmpty()) return null
        return zip to member
    }

    fun parse(path: Path): Pair<String, String>? = parse(path.toString())

    fun isZipPath(path: String): Boolean = path.startsWith(SCHEME)

    /**
     * Library/history [LocalGalleryEntity.contentPath] for a zip-as-dir gallery.
     * Member `.` means images at the zip root.
     */
    fun parseGallery(contentPath: String): Pair<String, String>? {
        val (zip, member) = parse(contentPath) ?: return null
        val inner = if (member == "." || member.isEmpty()) "" else member
        return zip to inner
    }
}
