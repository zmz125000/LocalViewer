package com.hippo.ehviewer.ui.theme

import android.app.WallpaperManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.scrollbar.LocalScrollbarStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ehviewer.core.ui.component.scrollbarStyle
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState

fun ColorScheme.amoled(amoled: Boolean) = if (amoled) {
    copy(
        surface = Color.Black,
        onSurface = Color.White,
        background = Color.Black,
        onBackground = Color.White,
    )
} else {
    this
}

@Composable
fun EhTheme(useDarkTheme: Boolean, content: @Composable () -> Unit) {
    val amoled by Settings.blackDarkTheme.collectAsState()
    val context = LocalContext.current
    // minSdk 32: Material You dynamic color is always available.
    val colors = if (useDarkTheme) {
        dynamicDarkColorScheme(context).amoled(amoled)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colors, motionScheme = CustomMotionScheme) {
        val scrollbarStyle = scrollbarStyle(color = MaterialTheme.colorScheme.primary)
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalScrollbarStyle provides scrollbarStyle,
            content = content,
        )
    }
}

@Composable
fun Color.scrim() = copy(alpha = if (isSystemInDarkTheme()) 0.5f else 0.9f)

typealias WallPaperPalette = Triple<Color, Color?, Color?>

@Composable
fun extractWallPaperPalette(): WallPaperPalette? {
    val colors = WallpaperManager.getInstance(LocalContext.current)?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
    val primary = colors.primaryColor.toArgb().let { Color(it) }
    val secondary = colors.secondaryColor?.toArgb()?.let { Color(it) }
    val tertiary = colors.tertiaryColor?.toArgb()?.let { Color(it) }
    return WallPaperPalette(primary, secondary, tertiary)
}

// https://issuetracker.google.com/363892346
object CustomMotionScheme : MotionScheme by MotionScheme.expressive() {
    override fun <T> defaultSpatialSpec() = defaultEffectsSpec<T>()
}
