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
     * Phone/tablet × portrait/landscape from [Configuration.smallestScreenWidthDp]
     * (stable across rotation) plus orientation. Tablet = sw ≥ 600dp.
     */
    @Composable
    private fun listLayout(): WindowListLayout {
        val configuration = LocalConfiguration.current
        val smallestWidthDp = configuration.smallestScreenWidthDp
        val landscape =
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return remember(smallestWidthDp, landscape) {
            windowListLayout(smallestWidthDp, landscape)
        }
    }

    @Composable
    fun listColumnCount(): Int = listLayout().columns

    @Composable
    fun capReaderSheet(): Boolean = listLayout().capReaderSheet

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

/**
 * Phone portrait 1 col / phone landscape 2 / tablet 2 / tablet landscape 3.
 * Reader sheets cap at 0.7 except phone landscape, which fills the screen.
 */
internal data class WindowListLayout(
    val columns: Int,
    val capReaderSheet: Boolean,
)

internal fun windowListLayout(smallestWidthDp: Int, landscape: Boolean): WindowListLayout {
    val tablet = smallestWidthDp >= WIDTH_DP_MEDIUM_LOWER_BOUND
    return when {
        tablet && landscape -> WindowListLayout(columns = 3, capReaderSheet = true)
        tablet -> WindowListLayout(columns = 2, capReaderSheet = true)
        landscape -> WindowListLayout(columns = 2, capReaderSheet = false)
        else -> WindowListLayout(columns = 1, capReaderSheet = true)
    }
}
