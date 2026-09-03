package com.hippo.ehviewer.library

import com.hippo.ehviewer.util.FileUtils

/**
 * Extensions treated as playable gallery images (folders, archives, MediaStore filter).
 *
 * Must stay aligned with native [supportExt] in `archive.c` and with formats Coil can
 * decode: Android BitmapFactory/ImageDecoder (minSdk 31) plus [coil3.svg.SvgDecoder].
 *
 * HDR / lib-still inventory (see [com.hippo.ehviewer.image.hdr.StillRoute]):
 * - Gain-map JPEG/AVIF/HEIC: Android 14+ platform decode.
 * - HEIC/HEIF (HEVC): platform ImageDecoder (not libavif).
 * - JPEG XR / JPEG XL: always lib → Ultra HDR JPEG (platform cannot open either).
 * - Absolute PQ/HLG **AVIF**: libavif → Ultra HDR when CICP sniff hits.
 * Native codecs link only arm64-v8a + x86_64 ([EHVIEWER_HDR_CODECS]).
 */
val IMAGE_EXTENSIONS = setOf(
    // JPEG (+ common aliases)
    "jpg", "jpeg", "jpe", "jfif",
    // Lossless / general raster
    "png", "webp", "gif", "bmp",
    // Android / Coil platform ImageDecoder (minSdk 31 — HEIC works like Aves)
    "heic", "heif", "heics", "heifs", "hif",
    "avif",
    "ico", "wbmp",
    // Coil [coil-svg]
    "svg", "svgz",
    // JPEG XR (Windows HDR screen capture) — converted to Ultra HDR before decode
    "jxr", "wdp", "hdp",
    // JPEG XL — converted to Ultra HDR before decode
    "jxl",
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

/** ustar/GNU TAR/CBT — store members; network reader uses chunk readahead (not ZIP CD). */
val TAR_ARCHIVE_EXTENSIONS = setOf("tar", "cbt")

fun isTarArchiveFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in TAR_ARCHIVE_EXTENSIONS
}

/** ZIP/CBZ — EOCD + central-directory index (range-friendly, no body walk). */
val ZIP_ARCHIVE_EXTENSIONS = setOf("zip", "cbz")

fun isZipArchiveFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in ZIP_ARCHIVE_EXTENSIONS
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

/**
 * Prefer mmap page-0 cover extract ([ArchiveCoverCache.ensureCover] non-solid branch).
 * Solid RAR/7z still get covers via sequential first-page extract in the same API.
 * Documents use [ArchiveCoverCache] document branch (not libarchive page-0).
 */
fun prefersArchiveCoverExtract(name: String): Boolean = isArchiveFileName(name) && !isSolidArchiveFileName(name) && !isDocumentFileName(name)

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
 * Common container formats treated as playable video in folder browse.
 * Tap → in-app Media3 player; long-press → external app via StreamDocumentProvider.
 */
val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv",
    "ts", "m2ts", "mts", "3gp", "mpg", "mpeg", "vob", "ogv",
)

fun isVideoFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
    return ext in VIDEO_EXTENSIONS
}

/** Leaf folder name excluded from video promote/tag (preview packs). */
fun isSampleDirName(name: String): Boolean = name.equals("sample", ignoreCase = true)

/**
 * Preview clip filenames (`sample-….mp4`) — still playable by extension, but not
 * tagged/promoted as browse video content.
 */
fun isSampleVideoFileName(name: String): Boolean {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    return base.startsWith("sample-", ignoreCase = true)
}

/** Video that counts for browse tags / promote (excludes sample previews). */
fun isBrowseVideoFileName(name: String): Boolean = isVideoFileName(name) && !isSampleVideoFileName(name)

/**
 * Browse video from filename and/or MediaStore MIME. DISPLAY_NAME is sometimes
 * a title without an extension; video MIME still counts (not sample-*).
 */
fun isBrowseVideoEntry(name: String, mimeType: String? = null): Boolean {
    if (isBrowseVideoFileName(name)) return true
    val mime = mimeType?.lowercase() ?: return false
    return mime.startsWith("video/") && !isSampleVideoFileName(name)
}

/**
 * Unknown / no-extension files. `application/octet-stream` matches almost no
 * ACTION_VIEW filters, so the system picker shows the wrong category (or none).
 * Wildcard MIME ([GENERIC_FILE_MIME]) is what file managers use so any handler
 * can appear.
 */
const val GENERIC_FILE_MIME = "*/*"

/** MIME for external open. Unknown extensions use [GENERIC_FILE_MIME]. */
fun mimeTypeForFileName(name: String): String {
    val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return GENERIC_FILE_MIME
    return when {
        ext in IMAGE_EXTENSIONS -> when (ext) {
            "jpg", "jpe", "jfif" -> "image/jpeg"
            "svg", "svgz" -> "image/svg+xml"
            "jxr", "wdp", "hdp" -> "image/vnd.ms-photo"
            "ico" -> "image/x-icon"
            "heics", "heifs", "hif" -> "image/heif"
            else -> "image/$ext"
        }
        ext in VIDEO_EXTENSIONS -> when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "mpg", "mpeg", "vob" -> "video/mpeg"
            "ogv" -> "video/ogg"
            else -> "video/*"
        }
        ext in ARCHIVE_EXTENSIONS -> when (ext) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "zip", "cbz" -> "application/zip"
            "rar", "cbr" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar", "cbt" -> "application/x-tar"
            else -> GENERIC_FILE_MIME
        }
        // Unknown extensions (incl. .exe, .iso, .img, …) → [GENERIC_FILE_MIME] so the
        // system "Open with" picker lists general handlers, not a niche type filter.
        else -> extraMimeForExtension(ext) ?: GENERIC_FILE_MIME
    }
}

/** Non-image / non-video / non-archive types opened as browse regular files. */
private fun extraMimeForExtension(ext: String): String? = when (ext) {
    "txt", "text", "log", "nfo", "ini", "conf", "cfg", "yml", "yaml" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "rtf" -> "application/rtf"
    "srt" -> "application/x-subrip"
    "ass", "ssa" -> "text/x-ssa"
    "vtt" -> "text/vtt"
    "smi", "sami" -> "application/smil+xml"
    "sub" -> "text/x-microdvd"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "wav" -> "audio/wav"
    "wma" -> "audio/x-ms-wma"
    "aiff", "aif" -> "audio/aiff"
    "ape" -> "audio/x-ape"
    "mka" -> "audio/x-matroska"
    "mid", "midi" -> "audio/midi"
    "amr" -> "audio/amr"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "apk", "xapk" -> "application/vnd.android.package-archive"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "gz" -> "application/gzip"
    "bz2" -> "application/x-bzip2"
    "xz" -> "application/x-xz"
    "zst" -> "application/zstd"
    "torrent" -> "application/x-bittorrent"
    else -> null
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
