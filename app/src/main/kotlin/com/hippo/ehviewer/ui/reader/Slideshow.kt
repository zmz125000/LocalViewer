package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

@Composable
fun slideshowAutoRotateMode(): Int {
    val mode by Settings.readingMode.collectAsState()
    val disable by Settings.slideshowDisableAutoRotate.collectAsState()
    val rotation by Settings.autoRotateMode.collectAsState()
    return if (mode == ReadingModeType.SLIDESHOW.prefValue && disable) AUTO_ROTATE_OFF else rotation
}

@Composable
fun slideshowScaleType(): Int {
    val mode by Settings.readingMode.collectAsState()
    val fillScreen by Settings.slideshowFillScreen.collectAsState()
    val scale by Settings.imageScaleType.collectAsState()
    return if (mode == ReadingModeType.SLIDESHOW.prefValue && fillScreen) 7 else scale
}
