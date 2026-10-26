package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun slideshowAutoRotateMode(): Int {
    val mode by Settings.readingMode.collectAsState()
    val override by Settings.slideshowAutoRotateOverride.collectAsState()
    val rotation by Settings.autoRotateMode.collectAsState()
    return if (mode == ReadingModeType.SLIDESHOW.prefValue && override >= 0) override else rotation
}

@Composable
fun slideshowScaleType(): Int {
    val mode by Settings.readingMode.collectAsState()
    val fillScreen by Settings.slideshowFillScreen.collectAsState()
    val scale by Settings.imageScaleType.collectAsState()
    return if (mode == ReadingModeType.SLIDESHOW.prefValue && fillScreen) 7 else scale
}

/** Pending decode/transition work blocks a new countdown, just like an active drag. */
internal data class SlideshowViewport(val page: Int, val blocked: Boolean, val hasPages: Boolean)

internal suspend fun runSlideshowTimer(
    viewports: kotlinx.coroutines.flow.Flow<SlideshowViewport>,
    awaitInterval: suspend () -> Unit,
    advance: suspend (Int) -> Unit,
) = kotlinx.coroutines.coroutineScope {
    val navigationScope = this
    viewports.collectLatest { viewport ->
        if (viewport.blocked || !viewport.hasPages) return@collectLatest
        while (true) {
            awaitInterval()
            // A viewport update cancels the countdown, but must not cancel the page turn
            // that caused it. A cancelled/no-op page turn also re-arms the countdown.
            navigationScope.launch { advance(viewport.page) }.join()
        }
    }
}
