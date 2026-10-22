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
    val grayscale by Settings.grayScale.collectAsState()
    val invert by Settings.invertedColors.collectAsState()
    return remember(enabled, preset, grayscale, invert) {
        if (!enabled || (preset !in 1..2 && !grayscale && !invert)) {
            null
        } else {
            val matrix = when (preset) {
                1 -> ColorMatrix(
                    floatArrayOf(
                        .393f, .769f, .189f, 0f, 0f,
                        .349f, .686f, .168f, 0f, 0f,
                        .272f, .534f, .131f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                2 -> ColorMatrix(
                    floatArrayOf(
                        1.5f, 0f, 0f, 0f, -63.75f,
                        0f, 1.5f, 0f, 0f, -63.75f,
                        0f, 0f, 1.5f, 0f, -63.75f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
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
