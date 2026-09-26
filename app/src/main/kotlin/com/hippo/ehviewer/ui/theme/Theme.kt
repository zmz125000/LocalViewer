package com.hippo.ehviewer.ui.theme

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.scrollbar.LocalScrollbarStyle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ehviewer.core.ui.component.scrollbarStyle
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState

fun ColorScheme.amoled(amoled: Boolean) = if (amoled) {
    copy(
        surface = Color.Black,
        onSurface = Color.White,
        onSurfaceVariant = OnDarkGreyMuted,
        background = Color.Black,
        onBackground = Color.White,
    )
} else {
    darkGrey()
}

/**
 * Regular dark theme. [amoled] keeps pure black for people who want that.
 * Neutrals are dark grey; accent colors stay from the dynamic scheme.
 * Text and icons use a light on-color so they stay readable on the grey.
 */
private fun ColorScheme.darkGrey() = copy(
    background = DarkGrey,
    surface = DarkGrey,
    surfaceDim = Color(0xFF222222),
    surfaceBright = Color(0xFF3C3C3C),
    surfaceContainerLowest = Color(0xFF1E1E1E),
    surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF303030),
    surfaceContainerHigh = Color(0xFF383838),
    surfaceContainerHighest = Color(0xFF424242),
    surfaceVariant = Color(0xFF3A3A3A),
    onBackground = OnDarkGrey,
    onSurface = OnDarkGrey,
    onSurfaceVariant = OnDarkGreyMuted,
    outline = Color(0xFF9A9A9A),
    outlineVariant = Color(0xFF5C5C5C),
)

private val DarkGrey = Color(0xFF2A2A2A)
private val OnDarkGrey = Color(0xFFECECEC)
private val OnDarkGreyMuted = Color(0xFFC8C8C8)

/** Dark-mode snackbar. Lighter than the page, still dark. */
private val SnackbarDark = Color(0xFF4A4A4A)

@Composable
fun snackbarContainerColor(): Color = if (isSystemInDarkTheme()) {
    SnackbarDark
} else {
    MaterialTheme.colorScheme.inverseSurface
}

/** Action label on a snackbar. Accent when it contrasts; otherwise plain light or dark. */
@Composable
fun snackbarActionContentColor(): Color {
    val preferred = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.inversePrimary
    }
    return snackbarOnBarColor(preferred)
}

/** Message and dismiss label. */
@Composable
fun snackbarDismissContentColor(): Color {
    val preferred = if (isSystemInDarkTheme()) {
        OnDarkGrey
    } else {
        MaterialTheme.colorScheme.inverseOnSurface
    }
    return snackbarOnBarColor(preferred)
}

@Composable
fun snackbarActionButtonColors() = ButtonDefaults.textButtonColors(contentColor = snackbarActionContentColor())

@Composable
fun snackbarDismissButtonColors() = ButtonDefaults.textButtonColors(contentColor = snackbarDismissContentColor())

@Composable
private fun snackbarOnBarColor(preferred: Color): Color {
    val bar = snackbarContainerColor()
    if (contrastRatio(preferred, bar) >= 4.5f) return preferred
    return if (bar.luminance() < 0.5f) Color.White else Color(0xFF1A1A1A)
}

private fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * @param applySystemBarAppearance When true (default), sync status/nav bar *icon* contrast
 * to [useDarkTheme] with normal logic (light UI → dark icons). Reader page themes pass false
 * so they do not override app / reader-owned bar icons.
 */
@Composable
fun EhTheme(
    useDarkTheme: Boolean,
    applySystemBarAppearance: Boolean = true,
    content: @Composable () -> Unit,
) {
    val amoled by Settings.blackDarkTheme.collectAsState()
    val context = LocalContext.current
    // minSdk 32: Material You dynamic color is always available.
    val colors = if (useDarkTheme) {
        dynamicDarkColorScheme(context).amoled(amoled)
    } else {
        dynamicLightColorScheme(context)
    }

    // Root app theme only. Nested reader EhTheme must not touch bars — that leaked white
    // icons into light mode after leaving the reader.
    val view = LocalView.current
    if (applySystemBarAppearance && !view.isInEditMode) {
        SideEffect {
            val window = context.findActivityOrNull()?.window ?: return@SideEffect
            val lightBars = !useDarkTheme
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
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

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
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
