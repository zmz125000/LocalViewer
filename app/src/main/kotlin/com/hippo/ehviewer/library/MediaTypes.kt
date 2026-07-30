package com.hippo.ehviewer.library

import com.hippo.ehviewer.util.FileUtils

val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
)

val ARCHIVE_EXTENSIONS = setOf(
    "zip",
    "cbz",
    "rar",
    "cbr",
    "7z",
    "cbt",
    "tar",
    // Image-only document extract (see DocumentExtractCache / EpubEngine / PdfImageEngine).
    "epub",
    "pdf",
)

/**
 * PDF / EPUB — comic-style image extract, not text reflow.
 * Listed like archives; reader uses [DocumentExtractCache], not libarchive solid/stream.
 */
val DOCUMENT_EXTENSIONS = setOf("epub", "pdf")

/**
 * Solid / poor-seek archives: no ZIP-style range stream.
 * Network open uses fake-stream sequential extract ([useSolidExtractPageLoader]);
 * browse lazy thumbs use sequential first-page extract ([ArchiveCoverCache.ensureSolidStreamCover]).
 */
val SOLID_ARCHIVE_EXTENSIONS = setOf("7z", "rar", "cbr")

/** Warn before downloading a remote archive larger than this (128 MiB). */
const val ARCHIVE_DOWNLOAD_WARN_BYTES = 128L * 1024L * 1024L

fun isSolidArchiveFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in SOLID_ARCHIVE_EXTENSIONS
}

fun isDocumentFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in DOCUMENT_EXTENSIONS
}

fun isEpubFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    return FileUtils.getExtensionFromFilename(name)?.lowercase() == "epub"
}

fun isPdfFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    return FileUtils.getExtensionFromFilename(name)?.lowercase() == "pdf"
}

/** True if [name] looks like a cached comic archive (protect from page-cache LRU). */
fun isArchiveCacheFileName(name: String): Boolean {
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in ARCHIVE_EXTENSIONS
}

/**
 * Prefer mmap page-0 cover extract ([ArchiveCoverCache.ensureCover] non-solid branch).
 * Solid RAR/7z still get covers via sequential first-page extract in the same API.
 * Documents use [ArchiveCoverCache] document branch (not libarchive page-0).
 */
fun prefersArchiveCoverExtract(name: String): Boolean =
    isArchiveFileName(name) && !isSolidArchiveFileName(name) && !isDocumentFileName(name)

/**
 * Remote path for an archive row in a browse listing.
 * Must match open-archive navigation and [ReaderGalleryPlaylist] keys.
 */
fun joinRemoteArchivePath(
    parentRelative: String,
    parentRelativeName: String,
    fileName: String,
): String {
    var p = parentRelative.trim('/')
    val mid = parentRelativeName.trim('/').let { if (it == ".") "" else it }
    if (mid.isNotEmpty()) p = if (p.isEmpty()) mid else "$p/$mid"
    val name = fileName.trim('/')
    return if (p.isEmpty()) name else "$p/$name"
}

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
 *
 * Must be a total order for [java.util.TimSort] (transitive, antisymetric).
 * The previous implementation used [Char.isDigit] (Unicode) but only stripped ASCII
 * `'0'`, and treated case-insensitive equal chars as fully equal without a
 * case-sensitive tie-break — both can throw
 * `Comparison method violates its general contract` on large WebDAV/SMB lists.
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    val na = a.length
    val nb = b.length
    while (i < na && j < nb) {
        val ca = a[i]
        val cb = b[j]
        val da = ca.isAsciiDigit()
        val db = cb.isAsciiDigit()
        if (da && db) {
            // Full digit runs (including leading zeros).
            val iRun = i
            val jRun = j
            while (i < na && a[i].isAsciiDigit()) i++
            while (j < nb && b[j].isAsciiDigit()) j++
            // Significant digits (skip leading zeros; all-zero → one zero).
            var iSig = iRun
            var jSig = jRun
            while (iSig < i - 1 && a[iSig] == '0') iSig++
            while (jSig < j - 1 && b[jSig] == '0') jSig++
            val lenA = i - iSig
            val lenB = j - jSig
            if (lenA != lenB) return lenA.compareTo(lenB)
            for (k in 0 until lenA) {
                val d = a[iSig + k].compareTo(b[jSig + k])
                if (d != 0) return d
            }
            // Same numeric value: shorter digit spelling first ("1" before "01"), then done.
            val runLen = (i - iRun).compareTo(j - jRun)
            if (runLen != 0) return runLen
        } else {
            // Case-insensitive primary; case-sensitive tie-break for total order.
            val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (d != 0) return d
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (na - i).compareTo(nb - j)
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
