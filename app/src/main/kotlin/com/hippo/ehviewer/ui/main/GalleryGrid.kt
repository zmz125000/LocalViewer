package com.hippo.ehviewer.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import kotlin.math.ceil

/**
 * Shared Library / Browse thumb-grid layout.
 *
 * Portrait columns = [Settings.thumbColumns] as set.
 * Landscape = that value × 1.5, rounded up (e.g. 3 → 5, 4 → 6).
 * Edge inset and inter-cell gutter come from tablet-qualified dimens.
 */
object GalleryGridDefaults {
    @Composable
    fun columnCount(): Int {
        val thumbColumns by Settings.thumbColumns.collectAsState()
        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        return remember(thumbColumns, landscape) {
            effectiveColumnCount(thumbColumns, landscape)
        }
    }

    @Composable
    fun columns(): GridCells = GridCells.Fixed(columnCount())

    /**
     * List-mode columns from [Configuration.smallestScreenWidthDp] (stable across
     * rotation) plus orientation:
     * phone portrait 1, phone landscape 2, tablet 3, tablet landscape 4.
     * Tablet = sw ≥ 600dp ([WIDTH_DP_MEDIUM_LOWER_BOUND] / `sw600dp`).
     */
    @Composable
    fun listColumnCount(): Int {
        val configuration = LocalConfiguration.current
        val smallestWidthDp = configuration.smallestScreenWidthDp
        val landscape =
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return remember(smallestWidthDp, landscape) {
            effectiveListColumnCount(smallestWidthDp, landscape)
        }
    }

    @Composable
    fun listColumns(): GridCells = GridCells.Fixed(listColumnCount())

    @Composable
    fun margin(): Dp = dimensionResource(R.dimen.gallery_grid_margin)

    @Composable
    fun gutter(): Dp = dimensionResource(R.dimen.gallery_grid_gutter)

    @Composable
    fun contentPadding(scaffoldPadding: PaddingValues = PaddingValues(0.dp)): PaddingValues {
        val m = margin()
        return scaffoldPadding + PaddingValues(m)
    }

    @Composable
    fun spacedBy(): Arrangement.HorizontalOrVertical = Arrangement.spacedBy(gutter())

    @Composable
    fun nameHeight(): Dp = dimensionResource(R.dimen.gallery_grid_name_height)

    @Composable
    fun namePaddingH(): Dp = dimensionResource(R.dimen.gallery_grid_name_padding_h)

    @Composable
    fun namePaddingBottom(): Dp = dimensionResource(R.dimen.gallery_grid_name_padding_bottom)
}

/** Portrait: setting as-is. Landscape: ceil(setting × 1.5). No width-class minimum. */
internal fun effectiveColumnCount(thumbColumns: Int, landscape: Boolean): Int {
    val base = thumbColumns.coerceIn(1, 10)
    if (!landscape) return base
    return ceil(base * 1.5).toInt().coerceAtLeast(1)
}

/** Phone 1 / phone landscape 2 / tablet 3 / tablet landscape 4. */
internal fun effectiveListColumnCount(smallestWidthDp: Int, landscape: Boolean): Int {
    val tablet = smallestWidthDp >= WIDTH_DP_MEDIUM_LOWER_BOUND
    return when {
        tablet && landscape -> 4
        tablet -> 3
        landscape -> 2
        else -> 1
    }
}
