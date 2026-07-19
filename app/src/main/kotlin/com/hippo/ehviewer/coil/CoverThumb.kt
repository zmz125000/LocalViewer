package com.hippo.ehviewer.coil

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.hippo.ehviewer.ktbuilder.imageRequest

/**
 * Decode/cache sizing for library & browse cover thumbs.
 *
 * Covers are decoded near on-screen size (not full comic resolution) so Coil's
 * memory cache can hold many more list/grid cells while scrolling.
 */
object CoverThumb {
    /** Browse list leading square (matches default cover thumb). */
    val ListDisplay = 56.dp

    /** Decode at 2× display size for crisp thumbs; clamp to avoid huge bitmaps. */
    fun decodePx(displayPx: Int): Int = (displayPx * 2).coerceIn(128, 768)

    @Composable
    fun listDecodePx(display: Dp = ListDisplay): Int {
        val density = LocalDensity.current
        return remember(display, density.density) {
            decodePx(with(density) { display.roundToPx() })
        }
    }

    /** Library list square ≈ card height. */
    @Composable
    fun libraryListDecodePx(cardHeight: Dp): Int {
        val density = LocalDensity.current
        return remember(cardHeight, density.density) {
            decodePx(with(density) { cardHeight.roundToPx() })
        }
    }

    /**
     * Grid cell edge from screen layout: (width − 2×margin − (cols−1)×gutter) / cols.
     */
    @Composable
    fun gridDecodePx(screenWidthDp: Int, columns: Int, margin: Dp, gutter: Dp): Int {
        val density = LocalDensity.current
        return remember(screenWidthDp, columns, margin, gutter, density.density) {
            val cols = columns.coerceAtLeast(1)
            val cellDp = ((screenWidthDp.dp - margin * 2 - gutter * (cols - 1)) / cols)
                .coerceAtLeast(80.dp)
            decodePx(with(density) { cellDp.roundToPx() })
        }
    }
}

/**
 * Configure a cover request for thumbnail use: fixed square decode, FILL crop,
 * INEXACT precision, and size-scoped cache keys (list vs grid do not thrash).
 */
fun ImageRequest.Builder.coverThumb(
    sizePx: Int,
    memoryKey: String,
    diskKey: String = memoryKey,
): ImageRequest.Builder {
    val edge = sizePx.coerceIn(64, 1024)
    size(edge, edge)
    scale(Scale.FILL)
    precision(Precision.INEXACT)
    memoryCacheKey("$memoryKey@$edge")
    diskCacheKey("$diskKey@$edge")
    return this
}

context(ctx: Context)
fun coverThumbRequest(
    data: Any,
    sizePx: Int,
    memoryKey: String,
    diskKey: String = memoryKey,
): ImageRequest = imageRequest {
    data(data)
    coverThumb(sizePx, memoryKey, diskKey)
}
