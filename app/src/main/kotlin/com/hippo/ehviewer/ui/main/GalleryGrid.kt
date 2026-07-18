package com.hippo.ehviewer.ui.main

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
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.ui.util.isExpanded
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState

/**
 * Shared Library / Browse thumb-grid layout.
 *
 * Columns use [Settings.thumbColumns] with a width-class floor so tablets are not stuck at 3.
 * Edge inset and inter-cell gutter come from tablet-qualified dimens.
 */
object GalleryGridDefaults {
    @Composable
    fun columnCount(): Int {
        val thumbColumns by Settings.thumbColumns.collectAsState()
        val widthDp = LocalConfiguration.current.screenWidthDp
        val expanded = LocalWindowSizeClass.current.isExpanded
        return remember(thumbColumns, widthDp, expanded) {
            effectiveColumnCount(thumbColumns, widthDp, expanded)
        }
    }

    @Composable
    fun columns(): GridCells = GridCells.Fixed(columnCount())

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

internal fun effectiveColumnCount(thumbColumns: Int, widthDp: Int, expanded: Boolean): Int {
    val floor = when {
        expanded || widthDp >= 840 -> 5
        widthDp >= 600 -> 4
        else -> 3
    }
    return thumbColumns.coerceAtLeast(floor).coerceIn(1, 10)
}
