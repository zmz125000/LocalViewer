package com.hippo.ehviewer.library

import com.hippo.ehviewer.util.FileUtils

/**
 * Sidecar subtitle discovery for external video open.
 *
 * Matches files in the same directory as the video:
 * - `movie.srt` / `movie.ass` / …
 * - `movie.en.srt`, `movie.zh-CN.ass`, … (basename + tags before extension)
 */
object SidecarSubtitles {
    private val EXTENSIONS = setOf(
        "srt",
        "ass",
        "ssa",
        "vtt",
        "ttml",
        "sub",
        "smi",
        "sami",
        "txt",
    )

    /** Language / region tags probed when directory listing is unavailable (network). */
    private val PROBE_TAGS = listOf(
        "",
        ".en", ".eng", ".en-US", ".en-GB",
        ".zh", ".zh-CN", ".zh-TW", ".zh-HK",
        ".chs", ".cht", ".sc", ".tc", ".chi",
        ".ja", ".jp", ".jpn",
        ".ko", ".kor",
        ".es", ".fr", ".de", ".ru", ".pt", ".it",
    )

    fun isSubtitleFileName(name: String): Boolean {
        if (name.startsWith('.')) return false
        val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return false
        return ext in EXTENSIONS
    }

    fun mimeTypeForSubtitle(name: String): String {
        val ext = FileUtils.getExtensionFromFilename(name)?.lowercase() ?: return "application/x-subrip"
        return when (ext) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            "ttml" -> "application/ttml+xml"
            "smi", "sami" -> "application/smil+xml"
            "sub" -> "text/x-microdvd"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * True if [fileName] is a sidecar for video [videoName]
     * (`movie.srt` or `movie.*.srt` matching stem `movie`).
     */
    fun isSidecarFor(videoName: String, fileName: String): Boolean {
        if (!isSubtitleFileName(fileName)) return false
        return matchesVideoStem(videoName, fileName)
    }

    /** Filter sibling basenames that pair with [videoName]. Sorted by name. */
    fun matchSiblings(videoName: String, siblingNames: Collection<String>): List<String> = siblingNames
        .asSequence()
        .filter { isSidecarFor(videoName, it) }
        .distinctBy { it.lowercase() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()

    /**
     * Candidate basenames to probe when we cannot list the directory
     * (exact stem + common language tags × subtitle extensions).
     */
    fun probeCandidateNames(videoName: String): List<String> {
        val videoStem = stem(videoName) ?: return emptyList()
        return buildList {
            for (tag in PROBE_TAGS) {
                for (ext in EXTENSIONS) {
                    // Skip bare .txt without a language tag — too many false positives.
                    if (ext == "txt" && tag.isEmpty()) continue
                    add("$videoStem$tag.$ext")
                }
            }
        }
    }

    internal fun matchesVideoStem(videoName: String, sidecarName: String): Boolean {
        val videoStem = stem(videoName) ?: return false
        val sidecarStem = stem(sidecarName) ?: return false
        return sidecarStem.equals(videoStem, ignoreCase = true) ||
            sidecarStem.startsWith("$videoStem.", ignoreCase = true) ||
            sidecarStem.startsWith("$videoStem ", ignoreCase = true)
    }

    internal fun trackSuffix(videoName: String, sidecarName: String): String? {
        val videoStem = stem(videoName) ?: return null
        if (!isSidecarFor(videoName, sidecarName)) return null
        return sidecarName.substringAfterLast('/').substringAfterLast('\\')
            .substring(videoStem.length).trimStart('.', ' ')
    }

    internal fun languageTag(suffix: String): String? {
        val firstTag = suffix.substringBeforeLast('.', "").substringBefore('.').substringBefore(' ').lowercase()
        return when (firstTag) {
            "chs", "sc" -> "zh-Hans"
            "cht", "tc" -> "zh-Hant"
            "jp" -> "ja"
            "cc", "sdh" -> null
            else -> firstTag.takeIf { LANGUAGE_TAG.matches(it) }
        }
    }

    private val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")

    private fun stem(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty() || !base.contains('.')) return null
        return base.substringBeforeLast('.')
    }
}
