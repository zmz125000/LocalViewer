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
    val preset by Settings.comicColorFilter.collectAsState()
    val sepiaStrength by Settings.comicSepiaStrength.collectAsState()
    val contrastStrength by Settings.comicContrastStrength.collectAsState()
    val grayscale by Settings.grayScale.collectAsState()
    val invert by Settings.invertedColors.collectAsState()
    return remember(enabled, preset, sepiaStrength, contrastStrength, grayscale, invert) {
        if (!enabled || (preset !in 1..2 && !grayscale && !invert)) {
            null
        } else {
            val matrix = when (preset) {
                1 -> {
                    val amount = sepiaStrength.coerceIn(0, 100) / 100f
                    // Interpolate from the original colors to the full sepia matrix.
                    ColorMatrix(
                        floatArrayOf(
                            1f - .607f * amount, .769f * amount, .189f * amount, 0f, 0f,
                            .349f * amount, 1f - .314f * amount, .168f * amount, 0f, 0f,
                            .272f * amount, .534f * amount, 1f - .869f * amount, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                }
                2 -> {
                    val scale = 1f + contrastStrength.coerceIn(0, 100) / 100f
                    val offset = 127.5f * (1f - scale)
                    ColorMatrix(
                        floatArrayOf(
                            scale, 0f, 0f, 0f, offset,
                            0f, scale, 0f, 0f, offset,
                            0f, 0f, scale, 0f, offset,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                }
                else -> ColorMatrix()
            }
            // Apply grayscale first, then the preset tint/contrast, then inversion.
            if (grayscale) matrix *= ColorMatrix().apply { setToSaturation(0f) }
            val result = if (invert) {
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ).apply { this *= matrix }
            } else {
                matrix
            }
            ColorFilter.colorMatrix(result)
        }
    }
}
