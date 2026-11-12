package com.hippo.ehviewer.library

import com.hippo.ehviewer.util.FileUtils

/** Audio files exposed beside an external HTTP video for player sidecar lookup. */
object SidecarAudioTracks {
    private val EXTENSIONS = setOf(
        "mka", "ac3", "eac3", "ec3", "dts", "dtshd",
        "aac", "m4a", "mp3", "flac", "opus", "ogg", "oga", "wav",
        "wma", "aif", "aiff", "ape",
    )

    fun isAudioFileName(name: String): Boolean {
        if (name.startsWith('.')) return false
        return FileUtils.getExtensionFromFilename(name)?.lowercase() in EXTENSIONS
    }

    fun matchSiblings(videoName: String, siblingNames: Collection<String>): List<String> = siblingNames
        .asSequence()
        .filter { isAudioFileName(it) && SidecarSubtitles.matchesVideoStem(videoName, it) }
        .distinctBy { it.lowercase() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()
}
