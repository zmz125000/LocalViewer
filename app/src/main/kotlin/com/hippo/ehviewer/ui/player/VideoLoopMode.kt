package com.hippo.ehviewer.ui.player

internal enum class VideoLoopMode {
    OFF,
    VIDEO,
    PLAYLIST,
    NEXT,
    EXIT,
    ;

    fun adjacentIndex(current: Int, count: Int, delta: Int): Int? {
        if (count < 2 || current !in 0 until count) return null
        val target = current + delta
        return if (this == PLAYLIST) Math.floorMod(target, count) else target.takeIf { it in 0 until count }
    }
}
