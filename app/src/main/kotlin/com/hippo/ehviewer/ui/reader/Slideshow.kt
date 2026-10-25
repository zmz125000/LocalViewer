package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

@Composable
fun slideshowAutoRotateMode(): Int {
    val mode by Settings.readingMode.collectAsState()
    val override by Settings.slideshowRotateOverride.collectAsState()
    val slideshowRotation by Settings.slideshowAutoRotateMode.collectAsState()
    val rotation by Settings.autoRotateMode.collectAsState()
    return if (mode == ReadingModeType.SLIDESHOW.prefValue && override) slideshowRotation else rotation
}

@Composable
fun slideshowScaleType(): Int {
    val mode by Settings.readingMode.collectAsState()
    val override by Settings.slideshowScaleOverride.collectAsState()
    val slideshowScale by Settings.slideshowScaleType.collectAsState()
    val scale by Settings.imageScaleType.collectAsState()
    return if (mode == ReadingModeType.SLIDESHOW.prefValue && override) slideshowScale else scale
}

/** One shuffled pass through pager slots, excluding the slot already being displayed. */
internal class SlideshowShuffle {
    private val remaining = mutableListOf<Int>()
    private var pageCount = -1

    fun next(current: Int, count: Int): Int? {
        if (count != pageCount) {
            pageCount = count
            reset(current, count)
        }
        remaining.remove(current)
        return if (remaining.isEmpty()) null else remaining.removeAt(remaining.lastIndex)
    }

    fun reset(current: Int, count: Int) {
        pageCount = count
        remaining.clear()
        remaining.addAll((0 until count).filter { it != current }.shuffled())
    }
}
