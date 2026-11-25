package com.hippo.ehviewer.ui.player

import android.content.Context
import android.util.AttributeSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar

@UnstableApi
class VideoScrubTimeBar(context: Context, attrs: AttributeSet?) : DefaultTimeBar(context, attrs) {
    private var dragPositionMs: Long? = null
    private var playerPositionMs = 0L

    override fun setPosition(position: Long) {
        playerPositionMs = position
        super.setPosition(dragPositionMs ?: position)
    }

    internal fun showDragPosition(positionMs: Long) {
        dragPositionMs = positionMs
        super.setPosition(positionMs)
    }

    internal fun finishDrag(positionMs: Long?) {
        dragPositionMs = null
        setPosition(positionMs ?: playerPositionMs)
    }
}
