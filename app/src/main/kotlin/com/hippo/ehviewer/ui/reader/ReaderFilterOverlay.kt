package com.hippo.ehviewer.ui.reader

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.platform.LocalContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.util.findActivity
import eu.kanade.tachiyomi.ui.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

/** Shared image/PDF reader controls, drawn below reader chrome and settings sheets. */
@Composable
fun ReaderFilterOverlay(supportsSlideshow: Boolean = true) {
    val context = LocalContext.current
    val activity = remember(context) { with(context) { findActivity<Activity>() } }
    val generalBrightness by Settings.customBrightness.collectAsState()
    val generalBrightnessValue by Settings.customBrightnessValue.collectAsState()
    val readingMode by Settings.readingMode.collectAsState()
    val slideshowBrightness by Settings.slideshowCustomBrightness.collectAsState()
    val slideshowBrightnessValue by Settings.slideshowCustomBrightnessValue.collectAsState()
    val override = supportsSlideshow && readingMode == ReadingModeType.SLIDESHOW.prefValue && slideshowBrightness
    val brightness = override || generalBrightness
    val brightnessValue = if (override) slideshowBrightnessValue else generalBrightnessValue
    val colorEnabled by Settings.colorFilter.collectAsState()
    val color by Settings.colorFilterValue.collectAsState()
    val mode by Settings.colorFilterMode.collectAsState()
    val blendMode = when (mode) {
        1 -> BlendMode.Multiply
        2 -> BlendMode.Screen
        3 -> BlendMode.Overlay
        4 -> BlendMode.Lighten
        5 -> BlendMode.Darken
        else -> BlendMode.SrcOver
    }
    ReaderContentOverlay(
        brightness = { brightnessValue }.takeIf { brightness && brightnessValue < 0 },
        color = { color }.takeIf { colorEnabled },
        colorBlendMode = blendMode,
    )
    LaunchedEffect(activity, brightness, brightnessValue) {
        activity.setCustomBrightnessValue(if (brightness) brightnessValue else 0)
    }
    DisposableEffect(activity) {
        onDispose { activity.setCustomBrightnessValue(0) }
    }
}
