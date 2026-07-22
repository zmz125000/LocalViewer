package com.hippo.ehviewer.coil

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.request.crossfade
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
 *
 * Do **not** call [com.ehviewer.core.files.toUri] on the main thread for MediaStore
 * paths — pass a [CoverPath] so [CoverPathFetcher] resolves off-main.
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
    // Instant paint on tab switch / recycle (global ImageLoader still has 120ms for other loads).
    crossfade(false)
    return this
}

context(ctx: Context)
fun coverThumbRequest(
    path: String,
    sizePx: Int,
    memoryKey: String = path,
    diskKey: String = memoryKey,
): ImageRequest = imageRequest {
    data(CoverPath(path))
    coverThumb(sizePx, memoryKey, diskKey)
}

context(ctx: Context)
fun coverThumbRequest(
    data: Any,
    sizePx: Int,
    memoryKey: String,
    diskKey: String = memoryKey,
): ImageRequest = imageRequest {
    // Prefer CoverPath for local path strings so MediaStore lookup is not on main.
    data(
        when (data) {
            is CoverPath -> data
            is String -> CoverPath(data)
            else -> data
        },
    )
    coverThumb(sizePx, memoryKey, diskKey)
}
