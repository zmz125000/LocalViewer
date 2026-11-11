package com.hippo.ehviewer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Palette stops are ordered from darkest to lightest for the theme roles. */
private data class Palette(val dark: Color, val deep: Color, val middle: Color, val pale: Color, val light: Color)

private fun palette(vararg colors: Long): Palette {
    // Curated palettes are sometimes arranged by hue rather than brightness.
    val stops = colors.map { Color(it) }.sortedBy { it.luminance() }
    return Palette(stops[0], stops[1], stops[2], stops[3], stops[4])
}

private val palettes = listOf(
    palette(SOLARIZED_DARK_BACKGROUND, SOLARIZED_BASE01, SOLARIZED_BASE00, SOLARIZED_BASE2, SOLARIZED_LIGHT_BACKGROUND), // Solarized Light
    palette(SOLARIZED_DARK_BACKGROUND, SOLARIZED_BASE02, SOLARIZED_BASE01, SOLARIZED_BASE0, SOLARIZED_BASE1), // Solarized Dark
    palette(0xFF5D3A66, 0xFFA77B84, 0xFFDBDDAF, 0xFFA9C3AD, 0xFFA8BFCC), // Sunset Serenade
    palette(0xFF23395B, 0xFF406E8E, 0xFF8EA8C3, 0xFFCBF7ED, 0xFFF4F4F9), // Coastal Breeze
    palette(0xFF2E4A3F, 0xFF3D6F5D, 0xFF7E9F8A, 0xFFC2D1C8, 0xFFF0F4F1), // Forest Hike
    palette(0xFFFF6B6B, 0xFFFFE66D, 0xFF4472CA, 0xFFA1C181, 0xFFF7F7FF), // Modern Pastel
    palette(0xFF6B4226, 0xFFC97C5D, 0xFFE7AD99, 0xFFF2D0A9, 0xFFF9EAE1), // Desert Dawn
    palette(0xFF233D4D, 0xFFFE7F2D, 0xFFFCCA46, 0xFFA1C181, 0xFF619B8A), // Aurora Glow
    palette(0xFF2F195F, 0xFFD7263D, 0xFFF46036, 0xFF2E294E, 0xFF1B998B), // Retro Pop
    palette(0xFF0D1B2A, 0xFF1B263B, 0xFF415A77, 0xFF778DA9, 0xFFE0E1DD), // Nordic Frost
    palette(0xFF2A1E5C, 0xFF643173, 0xFF8B5FBF, 0xFFD9BBF9, 0xFFF1EAFF), // Lavender Fields
    palette(0xFF05668D, 0xFF028090, 0xFF00A896, 0xFF02C39A, 0xFFF0F3BD), // Island Vibes
    palette(0xFF2D3142, 0xFF4F5D75, 0xFFBFC0C0, 0xFFFFFFFF, 0xFFEF8354), // Minimal Stone
    palette(0xFF3A3335, 0xFFD81E5B, 0xFFF0544F, 0xFFFDF0D5, 0xFFC6D8D3), // Soft Cream
    palette(0xFF001524, 0xFF15616D, 0xFFFFECD1, 0xFFFF7D00, 0xFF78290F), // Skyline
    palette(0xFF264653, 0xFF2A9D8F, 0xFFE9C46A, 0xFFF4A261, 0xFFE76F51), // Tea Garden
    palette(0xFF36213E, 0xFF554971, 0xFF63768D, 0xFF8AC6D0, 0xFFB8F3FF), // Blush & Ash
    palette(0xFF5E503F, 0xFFC6AC8F, 0xFFEAE0D5, 0xFFC1BDB3, 0xFFA9927D), // Soft Earth
    palette(0xFF1A535C, 0xFF4ECDC4, 0xFFF7FFF7, 0xFFFF6B6B, 0xFFFFE66D), // Citrus Fresh
    palette(0xFF0B132B, 0xFF1C2541, 0xFF3A506B, 0xFF5BC0BE, 0xFF6FFFE9), // Deep Sea
    palette(0xFF0F0E17, 0xFFFF8906, 0xFFF25F4C, 0xFFE53170, 0xFFA7A9BE), // Midnight Neon
    palette(0xFF3E2723, 0xFF6D4C41, 0xFFA1887F, 0xFFD7CCC8, 0xFFF5F5F5), // Mocha & Cream
    palette(0xFF2D2A32, 0xFFF49FB6, 0xFFFFD6A5, 0xFFFFF1E6, 0xFF90DBF4), // Peachy Dream
    palette(0xFF0B3D2E, 0xFF12664F, 0xFF13A571, 0xFF7FD1B9, 0xFFE8F6EF), // Emerald Lake
    palette(0xFF2B2D42, 0xFF8D99AE, 0xFFF2D0D9, 0xFFF9EAEA, 0xFFEDF2F4), // Dusty Rose
    palette(0xFF2C0735, 0xFF6A0572, 0xFFAB83A1, 0xFFEBD4E8, 0xFFF7F2F9), // Plum Sorbet
    palette(0xFF0F4C5C, 0xFF56A3A6, 0xFFFFC86E, 0xFFFF9F1C, 0xFFFFEBCB), // Tropical Mango
    palette(0xFF1B232A, 0xFF2D4A53, 0xFF86B3C4, 0xFFD0E4EE, 0xFFF7FBFF), // Glacier Mist
    palette(0xFF2F3E46, 0xFF354F52, 0xFF52796F, 0xFF84A98C, 0xFFCAD2C5), // Charcoal Mint
    palette(0xFF403D39, 0xFFE0AFA0, 0xFFF2E9E4, 0xFFA3C4BC, 0xFF6B9080), // Butterfly Garden
    palette(0xFF463F3A, 0xFFE0AFA0, 0xFFF4F3EE, 0xFFBCB8B1, 0xFF8A817C), // Sunbleached
    palette(0xFF14213D, 0xFF1F3C88, 0xFFA8DADC, 0xFFF1FAEE, 0xFFE9C46A), // Azure Sands
    palette(0xFF233D4D, 0xFF2A9D8F, 0xFF8AB17D, 0xFFE9C46A, 0xFFF4A261), // Jungle Feast
    palette(0xFF2A1A3D, 0xFF5C2A9D, 0xFFD90368, 0xFFF3C4FB, 0xFFFFE5EC), // Berry Smoothie
    palette(0xFF0E0E10, 0xFF1E1E24, 0xFF4E4E50, 0xFFD3D3D3, 0xFFFFFFFF), // Ink & Paper
    palette(0xFF3A4E48, 0xFF5E7C5E, 0xFFA7C4A0, 0xFFE3F2E1, 0xFFF8FFF4), // Sage & Olive
    palette(0xFF16324F, 0xFF1E6091, 0xFF3A86FF, 0xFFA9DEF9, 0xFFEAF4FF), // Cerulean Tide
    palette(0xFF2D1E2F, 0xFFEDAE49, 0xFFF9DF74, 0xFFF6E7CB, 0xFFFEF9E7), // Golden Hour
    palette(0xFF120309, 0xFF2E0F15, 0xFF5B2333, 0xFFA33F64, 0xFFF7CAD0), // Velvet Night
    palette(0xFF1F2833, 0xFF2C5F2D, 0xFF97BC62, 0xFFC5DCA0, 0xFFF1F7ED), // Copper Patina
    palette(0xFF2D2D44, 0xFFB8E1FF, 0xFFEFB0C9, 0xFFFFE0AC, 0xFFFFF7DF), // Pastel Sky
    palette(0xFF0B0A10, 0xFF00F5D4, 0xFF00BBF9, 0xFFFEE440, 0xFFF15BB5), // Neon Soda
    palette(0xFF1F1F1F, 0xFF5C4033, 0xFFCBB59D, 0xFFE6CCB2, 0xFFFAEDCD), // Vintage Film
    palette(0xFF00171F, 0xFF003459, 0xFF007EA7, 0xFF00A8E8, 0xFFE6F8FF), // Ocean Spray
    palette(0xFF2E2137, 0xFF7B2CBF, 0xFFC77DFF, 0xFFF3D1F4, 0xFFFFF0F6), // Blossom Fade
    palette(0xFF2A2D34, 0xFF774936, 0xFFB07D62, 0xFFFFE6A7, 0xFFFFF4D6), // Terra & Clay
    palette(0xFF0A0A0A, 0xFF3D5A80, 0xFF98C1D9, 0xFFE0FBFC, 0xFFEE6C4D), // Glass Morph
    palette(0xFF0B132B, 0xFF1C2541, 0xFF3A506B, 0xFF5BC0BE, 0xFFCFF9FF), // Arctic Dawn
    palette(0xFF2B193D, 0xFF5E315B, 0xFF964F8E, 0xFFE7C5EB, 0xFFFFF0FF), // Mulled Wine
    palette(0xFF2D3142, 0xFFEF476F, 0xFFFFD166, 0xFF06D6A0, 0xFF118AB2), // Candy Shop
    palette(0xFF3D2C2E, 0xFF8C5E58, 0xFFC17C74, 0xFFE6B8A2, 0xFFFFF0E6), // Sandstone
    palette(0xFF1E2D2F, 0xFF2D3E40, 0xFF5B8A8F, 0xFF9ACBD0, 0xFFEAF0F1), // Rainy Window
    palette(0xFF2D2424, 0xFF5C3D2E, 0xFFB85C38, 0xFFE0C097, 0xFFF7E2D5), // Coffee Break
    palette(0xFF2F1B25, 0xFF6B2737, 0xFFC05746, 0xFFFDB07D, 0xFFFFE7D1), // Canyon Sunset
    palette(0xFF1E2D2F, 0xFF2D4D4F, 0xFF6BCB77, 0xFFB8E6C8, 0xFFF2FFF7), // Mint Breeze
    palette(0xFF1A1423, 0xFF372549, 0xFF774C60, 0xFFB75D69, 0xFFEACDC2), // Royal Plum
    palette(0xFF0F2027, 0xFF203A43, 0xFF2C5364, 0xFF88D498, 0xFFE8F6EF), // Seaglass
    palette(0xFF0F0F0F, 0xFF3D3D3D, 0xFF7A7A7A, 0xFFBFD200, 0xFFE9F5A6), // Graphite & Lime
    palette(0xFF2C1A1D, 0xFF6E294E, 0xFFB66AAE, 0xFFF5C0C0, 0xFFFFF1F3), // Blush Cloud
    palette(0xFF132A13, 0xFF31572C, 0xFF4F772D, 0xFF90A955, 0xFFECF39E), // Tundra Moss
    palette(0xFF0B132B, 0xFF1C2541, 0xFF3A86FF, 0xFFFF6B6B, 0xFFFFD6A5), // Cobalt Ember
    palette(0xFF2F3E46, 0xFF52796F, 0xFFCAD2C5, 0xFFE9F5DB, 0xFFCCD5AE), // Stone & Pear
    palette(0xFF0D0C1D, 0xFF232946, 0xFFB8C1EC, 0xFFEAEAEA, 0xFFF6F7F8), // Twilight Ink
    palette(0xFF3B2C35, 0xFFA53860, 0xFFF49C6E, 0xFFFFD6A5, 0xFFFFF1E6), // Apricot Cream
    palette(0xFF0B1D26, 0xFF1B3A4B, 0xFF74C69D, 0xFFB7E4C7, 0xFFE9F5DB), // Polar Mint
    palette(0xFF220C10, 0xFF4A2040, 0xFF6D2E46, 0xFFF3A712, 0xFFFFF2B2), // Aubergine & Gold
    palette(0xFF0D1B2A, 0xFF1B263B, 0xFF4C6E91, 0xFFC57B57, 0xFFFFD9C0), // Denim & Rust
    palette(0xFF0B132B, 0xFF1B3A4B, 0xFF2E8A99, 0xFF98C1D9, 0xFFF1FAFF), // Lagoon Pearl
    palette(0xFF2E2A24, 0xFF6A8E7F, 0xFFA7C957, 0xFFF4ACB7, 0xFFFFE5EC), // Pistachio & Rose
    palette(0xFF0D1B2A, 0xFF415A77, 0xFF778DA9, 0xFFC2D3E9, 0xFFF1F5FF), // Moonstone
    palette(0xFF2A2A2A, 0xFF5D5A58, 0xFFC89B3C, 0xFFE6C57B, 0xFFFFF3C4), // Orchard Honey
    palette(0xFF1B2D2A, 0xFF2E4F4F, 0xFF84A98C, 0xFFCAD2C5, 0xFFF0EFEB), // Fog & Cedar
    palette(0xFF14213D, 0xFF1F3C88, 0xFFA8DADC, 0xFFFFE066, 0xFFFFF6D5), // Glacier Sun
    palette(0xFF344E41, 0xFF3A5A40, 0xFF588157, 0xFFA3B18A, 0xFFDAD7CD), // Sage Grove
    palette(0xFF012A4A, 0xFF01497C, 0xFF2A6F97, 0xFF61A5C2, 0xFFA9D6E5), // Blue Depths
    palette(0xFF212529, 0xFF495057, 0xFFADB5BD, 0xFFDEE2E6, 0xFFF8F9FA), // Slate Scale
    palette(0xFF03045E, 0xFF0077B6, 0xFF00B4D8, 0xFF90E0EF, 0xFFCAF0F8), // Azure Coast
)

internal const val SOLARIZED_LIGHT_PALETTE = 1
internal const val SOLARIZED_DARK_PALETTE = 2

/** Follow theme keeps the paired Solarized appearance when dark mode starts. */
internal fun selectedPaletteForTheme(lightSelection: Int, darkOverride: Int, dark: Boolean): Int {
    if (!dark) return lightSelection
    val selected = if (darkOverride >= 0) darkOverride else lightSelection
    return if (selected == SOLARIZED_LIGHT_PALETTE) SOLARIZED_DARK_PALETTE else selected
}

/** Returns null for Dynamic (system), including an unknown saved preference. */
internal fun paletteColorScheme(selection: Int, dark: Boolean, colorfulDark: Boolean = false): ColorScheme? = palettes.getOrNull(selection - 1)?.let { palette ->
    val colors = if (dark && colorfulDark) {
        palette.copy(dark = palette.deep, deep = palette.middle, middle = palette.pale, pale = palette.light)
    } else {
        palette
    }
    val scheme = colors.scheme(dark, colorfulDark)
    when {
        selection == SOLARIZED_LIGHT_PALETTE && !dark -> scheme.copy(
            onBackground = Color(SOLARIZED_LIGHT_FOREGROUND),
            onSurface = Color(SOLARIZED_LIGHT_FOREGROUND),
            onSurfaceVariant = Color(SOLARIZED_LIGHT_FOREGROUND),
        )
        selection == SOLARIZED_DARK_PALETTE && dark -> scheme.copy(
            onBackground = Color(SOLARIZED_DARK_FOREGROUND),
            onSurface = Color(SOLARIZED_DARK_FOREGROUND),
            onSurfaceVariant = Color(SOLARIZED_DARK_FOREGROUND),
        )
        else -> scheme
    }
}

private fun Palette.scheme(isDark: Boolean, colorfulDark: Boolean): ColorScheme {
    val surface = if (isDark) dark else light
    val toward = if (isDark) deep else middle

    // All container levels move in the same direction as Material's tonal elevation:
    // lighter in dark mode and darker in light mode.
    fun level(amount: Float) = lerp(surface, toward, amount)
    val foreground = if (isDark && colorfulDark) {
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
        surfaceContainerLow = level(if (isDark) 0.12f else 0.06f),
        surfaceContainer = level(if (isDark) 0.25f else 0.12f),
        surfaceContainerHigh = level(if (isDark) 0.42f else 0.25f),
        surfaceContainerHighest = level(if (isDark) 0.62f else 0.42f),
        surfaceVariant = level(if (isDark) 0.55f else 0.42f),
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
