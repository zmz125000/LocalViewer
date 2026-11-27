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
    val systemLevel: () -> Int,
    val adjustable: Boolean = true,
    val setLevel: (Int?) -> Int?,
)

internal fun videoLevelControlAt(x: Float, viewWidth: Int, swapped: Boolean): VideoLevelControl? = when {
    x < viewWidth * .25f -> if (swapped) VideoLevelControl.BRIGHTNESS else VideoLevelControl.VOLUME
    x >= viewWidth * .75f -> if (swapped) VideoLevelControl.VOLUME else VideoLevelControl.BRIGHTNESS
    else -> null
}

internal fun videoDragLevel(start: Int, dragY: Float, viewHeight: Int, range: IntRange, tablet: Boolean): Int {
    val steps = (range.last - range.first).toFloat()
    val fullRangeDrag = viewHeight.coerceAtLeast(1) * if (tablet) .25f else .5f
    return (start - dragY * steps / fullRangeDrag).roundToInt().coerceIn(range)
}
