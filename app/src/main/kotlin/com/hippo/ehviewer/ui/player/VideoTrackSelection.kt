package com.hippo.ehviewer.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.hippo.ehviewer.library.SidecarSubtitles

internal const val NO_VIDEO_SUBTITLES = ""
private const val NO_SUBTITLE_LANGUAGE = "-"

@UnstableApi
internal fun videoTrackKey(player: Player, type: Int): String? {
    val parameters = player.trackSelectionParameters
    if (type == C.TRACK_TYPE_TEXT && type in parameters.disabledTrackTypes) {
        return NO_VIDEO_SUBTITLES
    }
    return videoTrackOverrides(player, type).firstOrNull { (_, track) ->
        parameters.overrides[track.mediaTrackGroup]?.trackIndices?.contains(track.trackIndices.single()) == true
    }?.first
}

@UnstableApi
internal fun TrackSelectionParameters.Builder.restoreVideoTrack(player: Player, type: Int, key: String?): Boolean {
    if (key == null) return false
    if (type == C.TRACK_TYPE_TEXT && key == NO_VIDEO_SUBTITLES) {
        hideVideoSubtitles()
    } else {
        val override = videoTrackOverrides(player, type).firstOrNull { it.first == key }?.second ?: return false
        selectVideoTrack(override)
    }
    return true
}

@UnstableApi
internal fun TrackSelectionParameters.Builder.hideVideoSubtitles() = clearOverridesOfType(C.TRACK_TYPE_TEXT)
    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)

@UnstableApi
internal fun TrackSelectionParameters.Builder.selectVideoTrack(track: TrackSelectionOverride) = setTrackTypeDisabled(track.type, false).setOverrideForType(track)

internal data class SiblingTrackChoice(
    val tracks: List<VideoTrackIdentity>,
    val selectedIndex: Int,
)

internal data class VideoTrackIdentity(
    val language: String?,
    val label: String?,
    val mimeType: String?,
    val codecs: String?,
    val roles: Int,
    val flags: Int,
    val channels: Int,
)

@UnstableApi
private fun TrackSelectionOverride.format(): Format = mediaTrackGroup.getFormat(trackIndices.single())

@UnstableApi
private fun Format.sidecarSuffix(item: MediaItem?): String? {
    val title = item?.mediaMetadata?.title?.toString() ?: return null
    val label = label ?: return null
    if (item.localConfiguration?.subtitleConfigurations?.none { it.label == label } != false) return null
    return SidecarSubtitles.trackSuffix(title, label)
}

@UnstableApi
private fun TrackSelectionOverride.siblingIdentity(item: MediaItem?) = format().let {
    VideoTrackIdentity(
        normalizeVideoLanguage(it.language),
        it.sidecarSuffix(item) ?: it.label,
        it.sampleMimeType,
        it.codecs,
        it.roleFlags,
        it.selectionFlags,
        it.channelCount,
    )
}

@UnstableApi
internal fun Player.siblingTrackChoice(type: Int): SiblingTrackChoice? {
    val tracks = videoTrackOverrides(this, type).map { it.second }.toList()
    if (tracks.isEmpty()) return null
    val parameters = trackSelectionParameters
    val identities = tracks.map { it.siblingIdentity(currentMediaItem) }
    if (type == C.TRACK_TYPE_TEXT && type in parameters.disabledTrackTypes) return SiblingTrackChoice(identities, -1)
    val explicit = tracks.indexOfFirst { parameters.overrides[it.mediaTrackGroup] == it }
    val selectedIndex = if (explicit >= 0) {
        explicit
    } else {
        val groups = currentTracks.groups
        tracks.indexOfFirst { track ->
            groups.any { it.mediaTrackGroup == track.mediaTrackGroup && it.isTrackSelected(track.trackIndices.single()) }
        }
    }
    return if (selectedIndex >= 0) SiblingTrackChoice(identities, selectedIndex) else null
}

@UnstableApi
internal fun TrackSelectionParameters.Builder.restoreSiblingTrack(player: Player, type: Int, previous: SiblingTrackChoice?): Boolean {
    if (previous == null) return false
    val tracks = videoTrackOverrides(player, type).map { it.second }.toList()
    if (tracks.map { it.siblingIdentity(player.currentMediaItem) } != previous.tracks) return false
    if (previous.selectedIndex < 0) hideVideoSubtitles() else selectVideoTrack(tracks[previous.selectedIndex])
    return true
}

@UnstableApi
private fun normalizeVideoLanguage(language: String?): String? {
    if (language == null) return null
    val normalized: String = Util.normalizeLanguageCode(language)
    return normalized.takeIf { it.isNotEmpty() && it != "und" && ',' !in it && it != NO_SUBTITLE_LANGUAGE }
}

@UnstableApi
internal fun Player.subtitleLanguage(format: Format): String? = normalizeVideoLanguage(format.language)
    ?: format.sidecarSuffix(currentMediaItem)?.let { normalizeVideoLanguage(SidecarSubtitles.languageTag(it)) }

internal fun updateVideoSubtitleHistory(history: String, choice: String): String {
    val language = choice.ifEmpty { NO_SUBTITLE_LANGUAGE }
    val choices = (listOf(language) + history.split(',')).filter { it.isNotEmpty() }.distinct()
    val languages = choices.filter { it != NO_SUBTITLE_LANGUAGE }.take(3)
    return choices.filter { it == NO_SUBTITLE_LANGUAGE || it in languages }.joinToString(",")
}

@UnstableApi
internal fun TrackSelectionParameters.Builder.applyVideoSubtitleHistory(player: Player, history: String): Boolean {
    if (history.isEmpty()) return false
    val tracks = videoTrackOverrides(player, C.TRACK_TYPE_TEXT).map { it.second }.toList()
    for (language in history.split(',')) {
        if (language == NO_SUBTITLE_LANGUAGE) {
            hideVideoSubtitles()
            return true
        }
        if (language.isEmpty()) continue
        val match = tracks.mapNotNull { track ->
            val format = track.format()
            val tag = player.subtitleLanguage(format) ?: return@mapNotNull null
            val languageScore = when {
                tag == language -> 2
                tag.startsWith("$language-") || language.startsWith("$tag-") -> 1
                else -> return@mapNotNull null
            }
            val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
            track to (languageScore + if (forced) 0 else 2)
        }.maxByOrNull { it.second }?.first ?: continue
        selectVideoTrack(match)
        return true
    }
    return false
}

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
