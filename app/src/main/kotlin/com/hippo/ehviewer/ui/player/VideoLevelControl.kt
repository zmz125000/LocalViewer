package com.hippo.ehviewer.ui.player

import com.hippo.ehviewer.R
import kotlin.math.roundToInt

internal enum class VideoLevelControl(val title: Int) {
    VOLUME(R.string.video_volume),
    BRIGHTNESS(R.string.video_brightness),
    ;

    fun icon(level: Int?): Int = when (this) {
        VOLUME -> if (level == 0) R.drawable.ic_video_volume_muted else R.drawable.ic_video_volume
        BRIGHTNESS -> R.drawable.ic_video_brightness
    }
}

/** Levels and callbacks use the same units as the corresponding slider. */
internal class VideoLevelAdjustment(
    val range: IntRange,
    val level: Int?,
    val systemLevel: Int,
    val adjustable: Boolean = true,
    val setLevel: (Int?) -> Int?,
)

internal fun videoLevelControlAt(x: Float, viewWidth: Int): VideoLevelControl? = when {
    x < viewWidth * .25f -> VideoLevelControl.VOLUME
    x >= viewWidth * .75f -> VideoLevelControl.BRIGHTNESS
    else -> null
}

/** Sliding up by half a screen covers the slider's full range. */
internal fun videoDragLevel(start: Int, dragY: Float, viewHeight: Int, range: IntRange): Int {
    val steps = (range.last - range.first).toFloat()
    return (start - dragY * steps / (viewHeight.coerceAtLeast(1) * .5f)).roundToInt().coerceIn(range)
}
