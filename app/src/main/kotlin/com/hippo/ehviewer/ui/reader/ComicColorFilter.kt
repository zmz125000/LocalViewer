package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState

/** Draw-time comic effects; callers bypass these for HDR and wide-gamut images. */
@Composable
fun rememberComicColorFilter(enabled: Boolean = true): ColorFilter? {
    val effects by Settings.comicColorFilter.collectAsState()
    val sepiaStrength by Settings.comicSepiaStrength.collectAsState()
    val contrastStrength by Settings.comicContrastStrength.collectAsState()
    val levelsEnabled by Settings.comicLevelsEnabled.collectAsState()
    val blackLevel by Settings.comicBlackLevel.collectAsState()
    val whiteLevel by Settings.comicWhiteLevel.collectAsState()
    val grayscale by Settings.grayScale.collectAsState()
    val invert by Settings.invertedColors.collectAsState()
    return remember(enabled, effects, sepiaStrength, contrastStrength, levelsEnabled, blackLevel, whiteLevel, grayscale, invert) {
        val white = whiteLevel.coerceIn(128, 255)
        val black = blackLevel.coerceIn(0, 127)
        val levels = levelsEnabled && (black != 0 || white != 255)
        val sepia = effects and 1 != 0 && sepiaStrength > 0
        val contrast = effects and 2 != 0 && contrastStrength > 0
        if (!enabled || (!sepia && !contrast && !levels && !grayscale && !invert)) {
            null
        } else {
            // Input black -> 0 and input white -> 255. Neutral defaults are identity.
            // With white=255, raising black darkens gray lines and preserves white.
            var matrix = if (levels) {
                val levelScale = 255f / (white - black)
                channelScaleMatrix(levelScale, -black * levelScale)
            } else {
                ColorMatrix()
            }
            if (grayscale) matrix *= ColorMatrix().apply { setToSaturation(0f) }
            if (contrast) {
                val scale = 1f + contrastStrength.coerceIn(0, 100) / 100f
                matrix = channelScaleMatrix(scale, 127.5f * (1f - scale)).apply { this *= matrix }
            }
            if (sepia) {
                val amount = sepiaStrength.coerceIn(0, 100) / 100f
                matrix = ColorMatrix(
                    floatArrayOf(
                        1f - .607f * amount, .769f * amount, .189f * amount, 0f, 0f,
                        .349f * amount, 1f - .314f * amount, .168f * amount, 0f, 0f,
                        .272f * amount, .534f * amount, 1f - .869f * amount, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ).apply { this *= matrix }
            }
            if (invert) matrix = channelScaleMatrix(-1f, 255f).apply { this *= matrix }
            ColorFilter.colorMatrix(matrix)
        }
    }
}

private fun channelScaleMatrix(scale: Float, offset: Float) = ColorMatrix(
    floatArrayOf(
        scale, 0f, 0f, 0f, offset,
        0f, scale, 0f, 0f, offset,
        0f, 0f, scale, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    ),
)
