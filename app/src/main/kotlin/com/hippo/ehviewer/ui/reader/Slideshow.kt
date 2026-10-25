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
