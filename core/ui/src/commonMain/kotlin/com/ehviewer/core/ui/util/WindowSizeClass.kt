package com.ehviewer.core.ui.util

import androidx.compose.runtime.compositionLocalOf
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND

val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> { error("CompositionLocal LocalWindowSizeClass not present!") }

/** Width ≥ 600dp — tablets / large phones in landscape; prefer NavigationRail. */
val WindowSizeClass.isMediumWidthOrWider
    get() = isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)

val WindowSizeClass.isExpanded
    get() = isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)
