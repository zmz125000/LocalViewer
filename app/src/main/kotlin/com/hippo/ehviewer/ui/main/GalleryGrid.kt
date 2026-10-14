package com.hippo.ehviewer.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.ui.util.isMediumWidthOrWider
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.collectAsState

/**
 * Shared Library / Browse thumb-grid layout.
 *
 * [Settings.gridCellSize] is the minimum cell edge. [GridCells.Adaptive] then
 * stretches cells so the row fills the width; [gutter] and [margin] stay fixed.
 * Column count follows the window, including fold and free-form sizes.
 */
object GalleryGridDefaults {
    /** M3 NavigationRail container width. Medium+ windows place it beside the grid. */
    private val MainNavRailWidth = 80.dp

    @Composable
    fun cellSize(): Dp {
        val stored by Settings.gridCellSize.collectAsState()
        val dp = stored.coerceIn(Settings.GRID_CELL_MIN_DP, Settings.GRID_CELL_MAX_DP)
        return dp.dp
    }

    /**
     * Columns that fit [cellSize] in the current content width, using the same
     * count as [GridCells.Adaptive]. Extra pixels are shared by the cells.
     */
    @Composable
    fun columnCount(): Int {
        val minCell = cellSize()
        val gutter = gutter()
        val available = contentWidth()
        val density = LocalDensity.current
        return remember(minCell, gutter, available, density) {
            with(density) {
                adaptiveColumnCount(
                    available.roundToPx(),
                    minCell.roundToPx(),
                    gutter.roundToPx(),
                )
            }
        }
    }

    @Composable
    fun columns(): GridCells = GridCells.Adaptive(cellSize())

    /** Decode edge for a fitted grid cell (after stretch), not the raw minimum. */
    @Composable
    fun gridDecodePx(): Int {
        val columns = columnCount()
        val gutter = gutter()
        val available = contentWidth()
        val density = LocalDensity.current
        return remember(columns, gutter, available, density) {
            val cols = columns.coerceAtLeast(1)
            val cell = ((available - gutter * (cols - 1)) / cols).coerceAtLeast(1.dp)
            CoverThumb.decodePx(with(density) { cell.roundToPx() })
        }
    }

    /**
     * Width the main grid actually lays out in: window width, minus the nav rail
     * on medium+ windows, minus horizontal [margin].
     */
    @Composable
    private fun contentWidth(): Dp {
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val rail = if (LocalWindowSizeClass.current.isMediumWidthOrWider) {
            MainNavRailWidth
        } else {
            0.dp
        }
        val horizontalInset = margin() * 2
        return (screenWidthDp.dp - rail - horizontalInset).coerceAtLeast(0.dp)
    }

    /**
     * Phone/tablet × portrait/landscape from [Configuration.smallestScreenWidthDp]
     * (stable across rotation) plus orientation. Tablet = sw ≥ 600dp.
     */
    @Composable
    internal fun listLayout(): WindowListLayout {
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

    /** Dark page is #2A2A2A (or black). Cells sit a step lighter so the card reads. */
    @Composable
    fun cardColors(): CardColors = if (isSystemInDarkTheme()) {
        CardDefaults.elevatedCardColors(containerColor = Color(0xFF3A3A3A))
    } else {
        CardDefaults.elevatedCardColors()
    }

    @Composable
    fun margin(): Dp = dimensionResource(R.dimen.gallery_grid_margin)

    @Composable
    fun gutter(): Dp = dimensionResource(R.dimen.gallery_grid_gutter)

    @Composable
    fun contentPadding(scaffoldPadding: PaddingValues = PaddingValues(0.dp)): PaddingValues {
        val m = margin()
        return scaffoldPadding + PaddingValues(m)
    }

    /** Top and bottom match [contentPadding]. No horizontal inset, so list rows stay full width. */
    @Composable
    fun listVerticalContentPadding(): PaddingValues {
        val m = margin()
        return PaddingValues(top = m, bottom = m)
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

/**
 * Column count for [androidx.compose.foundation.lazy.grid.GridCells.Adaptive]:
 * `(available + gutter) / (minCell + gutter)`, at least 1.
 * Leftover width is not a gap; the grid splits it across the cells.
 */
internal fun adaptiveColumnCount(availablePx: Int, minCellPx: Int, gutterPx: Int): Int {
    val min = minCellPx.coerceAtLeast(1)
    val gutter = gutterPx.coerceAtLeast(0)
    val available = availablePx.coerceAtLeast(0)
    return ((available + gutter) / (min + gutter)).coerceAtLeast(1)
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
