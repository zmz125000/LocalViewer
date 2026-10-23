package com.hippo.ehviewer.ui.reader

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.platform.LocalContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.util.findActivity
import eu.kanade.tachiyomi.ui.reader.ReaderContentOverlay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.sample

/** Shared image/PDF reader controls, drawn below reader chrome and settings sheets. */
@Composable
fun ReaderFilterOverlay() {
    val context = LocalContext.current
    val activity = remember(context) { with(context) { findActivity<Activity>() } }
    val brightness by Settings.customBrightness.collectAsState()
    val brightnessValue by Settings.customBrightnessValue.collectAsState()
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
    if (brightness) {
        LaunchedEffect(activity) {
            Settings.customBrightnessValue.valueFlow().sample(100)
                .onCompletion { activity.setCustomBrightnessValue(0) }
                .collect { activity.setCustomBrightnessValue(it) }
        }
    }
}
