package com.hippo.ehviewer.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi

private const val NO_VIDEO_SUBTITLES = ""
private const val KEEP_FORCED_SUBTITLE_FLAGS = C.SELECTION_FLAG_FORCED.inv()

@UnstableApi
internal fun videoTrackKey(player: Player, type: Int): String? {
    val parameters = player.trackSelectionParameters
    if (type == C.TRACK_TYPE_TEXT && parameters.ignoredTextSelectionFlags == KEEP_FORCED_SUBTITLE_FLAGS &&
        parameters.overrides.values.none { it.type == type }
    ) {
        return NO_VIDEO_SUBTITLES
    }
    return videoTrackOverrides(player, type).firstOrNull { (_, track) ->
        parameters.overrides[track.mediaTrackGroup]?.trackIndices?.contains(track.trackIndices.single()) == true
    }?.first
}

@UnstableApi
internal fun TrackSelectionParameters.Builder.restoreVideoTrack(player: Player, type: Int, key: String?) {
    if (key == null) return
    if (type == C.TRACK_TYPE_TEXT && key == NO_VIDEO_SUBTITLES) {
        hideVideoSubtitles()
    } else {
        videoTrackOverrides(player, type).firstOrNull { it.first == key }?.let { setOverrideForType(it.second) }
    }
}

@UnstableApi
internal fun TrackSelectionParameters.Builder.hideVideoSubtitles() = clearOverridesOfType(C.TRACK_TYPE_TEXT)
    .setIgnoredTextSelectionFlags(KEEP_FORCED_SUBTITLE_FLAGS)
    .setPreferredTextLanguage(null)
    .setPreferredTextRoleFlags(0)

@UnstableApi
private fun videoTrackOverrides(player: Player, type: Int): Sequence<Pair<String, TrackSelectionOverride>> = sequence {
    val occurrences = mutableMapOf<String, Int>()
    for (group in player.currentTracks.groups) {
        if (group.type != type) continue
        for (index in 0 until group.length) {
            val format = group.getTrackFormat(index)
            // Occurrences disambiguate tracks whose containers omit unique IDs.
            val identity = listOf(format.id, format.label, format.language, format.sampleMimeType).joinToString("|")
            val occurrence = occurrences.getOrDefault(identity, 0)
            occurrences[identity] = occurrence + 1
            if (group.isTrackSupported(index)) {
                yield("$occurrence:$identity" to TrackSelectionOverride(group.mediaTrackGroup, index))
            }
        }
    }
}
