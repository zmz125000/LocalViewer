package com.hippo.ehviewer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Palette stops are ordered from darkest to lightest, as in the supplied palettes. */
private data class Palette(val dark: Color, val deep: Color, val middle: Color, val pale: Color, val light: Color)

private fun palette(vararg colors: Long) = Palette(
    Color(colors[0]),
    Color(colors[1]),
    Color(colors[2]),
    Color(colors[3]),
    Color(colors[4]),
)

private val palettes = listOf(
    palette(0xFF0D1B2A, 0xFF415A77, 0xFF778DA9, 0xFFC2D3E9, 0xFFF1F5FF), // Moonstone
    palette(0xFF0D0C1D, 0xFF232946, 0xFFB8C1EC, 0xFFEAEAEA, 0xFFF6F7F8), // Twilight Ink
    palette(0xFF00171F, 0xFF003459, 0xFF007EA7, 0xFF00A8E8, 0xFFE6F8FF), // Ocean Spray
    palette(0xFF0D1B2A, 0xFF1B263B, 0xFF415A77, 0xFF778DA9, 0xFFE0E1DD), // Nordic Frost
    palette(0xFF2E4A3F, 0xFF3D6F5D, 0xFF7E9F8A, 0xFFC2D1C8, 0xFFF0F4F1), // Forest Hike
    palette(0xFF1B232A, 0xFF2D4A53, 0xFF86B3C4, 0xFFD0E4EE, 0xFFF7FBFF), // Glacier Mist
    palette(0xFF0E0E10, 0xFF1E1E24, 0xFF4E4E50, 0xFFD3D3D3, 0xFFFFFFFF), // Ink Paper
    palette(0xFF16324F, 0xFF1E6091, 0xFF3A86FF, 0xFFA9DEF9, 0xFFEAF4FF), // Cerulean Tide
    palette(0xFF1F2833, 0xFF2C5F2D, 0xFF97BC62, 0xFFC5DCA0, 0xFFF1F7ED), // Copper Patina
    palette(0xFF1B2D2A, 0xFF2E4F4F, 0xFF84A98C, 0xFFCAD2C5, 0xFFF0EFEB), // Fog Cedar
    palette(0xFF0B132B, 0xFF1B3A4B, 0xFF2E8A99, 0xFF98C1D9, 0xFFF1FAFF), // Lagoon Pearl
    palette(0xFF0B1D26, 0xFF1B3A4B, 0xFF74C69D, 0xFFB7E4C7, 0xFFE9F5DB), // Polar Mint
    palette(0xFF1E2D2F, 0xFF2D3E40, 0xFF5B8A8F, 0xFF9ACBD0, 0xFFEAF0F1), // Rainy Window
    palette(0xFF2B193D, 0xFF5E315B, 0xFF964F8E, 0xFFE7C5EB, 0xFFFFF0FF), // Mulled Wine
    palette(0xFF2D1E2F, 0xFFEDAE49, 0xFFF9DF74, 0xFFF6E7CB, 0xFFFEF9E7), // Golden Hour
)

/** Returns null for Dynamic (system), including an unknown saved preference. */
internal fun paletteColorScheme(selection: Int, dark: Boolean, lighterDark: Boolean = false): ColorScheme? = palettes.getOrNull(selection - 1)?.let { palette ->
    val colors = if (dark && lighterDark) {
        palette.copy(dark = palette.deep, deep = palette.middle, middle = palette.pale, pale = palette.light)
    } else {
        palette
    }
    colors.scheme(dark, lighterDark)
}

private fun Palette.scheme(isDark: Boolean, lighterDark: Boolean): ColorScheme {
    val surface = if (isDark) dark else light
    val toward = if (isDark) deep else middle

    // All container levels move in the same direction as Material's tonal elevation:
    // lighter in dark mode and darker in light mode.
    fun level(amount: Float) = lerp(surface, toward, amount)
    val foreground = if (isDark && lighterDark) {
        readableOn(surface)
    } else if (isDark) {
        light
    } else {
        dark
    }
    val primary = if (isDark) pale else deep
    val secondary = if (isDark) middle else deep
    val tertiary = if (isDark) light else dark
    val primaryContainer = if (isDark) deep else pale
    val secondaryContainer = if (isDark) lerp(deep, middle, 0.25f) else lerp(pale, middle, 0.2f)
    val tertiaryContainer = if (isDark) lerp(deep, middle, 0.5f) else lerp(pale, middle, 0.35f)
    return (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(primaryContainer),
        inversePrimary = if (isDark) deep else pale,
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(secondaryContainer),
        tertiary = tertiary,
        onTertiary = readableOn(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = readableOn(tertiaryContainer),
        background = surface,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        surfaceDim = if (isDark) dark else level(0.25f),
        surfaceBright = if (isDark) level(0.9f) else light,
        surfaceContainerLowest = if (isDark) dark else light,
        surfaceContainerLow = level(0.12f),
        surfaceContainer = level(0.25f),
        surfaceContainerHigh = level(0.42f),
        surfaceContainerHighest = level(0.62f),
        surfaceVariant = level(0.55f),
        onSurfaceVariant = foreground,
        outline = if (isDark) middle else deep,
        outlineVariant = level(0.9f),
        inverseSurface = if (isDark) light else dark,
        inverseOnSurface = if (isDark) dark else light,
        surfaceTint = primary,
    )
}

private fun readableOn(background: Color): Color {
    val black = Color(0xFF0A0A0A)
    val white = Color.White
    fun contrast(foreground: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
    return if (contrast(black) >= contrast(white)) black else white
}
