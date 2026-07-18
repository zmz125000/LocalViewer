package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.hippo.ehviewer.Settings
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegions
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.getAction
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomableState

/**
 * Double-tap handler.
 * - [Settings.doubleTapToZoom] on → zoom in/out (original behavior).
 * - off → prev/next **gallery folder** on edge zones; **MENU (center)** acts like system back.
 */
fun doubleTapAction(
    isRtl: Boolean,
    getViewportSize: () -> Size,
    getNavigator: () -> NavigationRegions,
    onPrevFolder: () -> Unit,
    onNextFolder: () -> Unit,
    onBack: () -> Unit = {},
): DoubleClickToZoomListener = object : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        if (Settings.doubleTapToZoom.value) {
            val zoomFraction = state.zoomFraction ?: return
            if (zoomFraction > 0.05f) {
                state.resetZoom()
            } else {
                // Workaround for https://github.com/saket/telephoto/issues/45
                state.zoomTo(
                    zoomFactor = state.contentTransformation.scaleMetadata.initialScale.scaleX * 2f,
                    centroid = centroid,
                )
            }
            return
        }

        val size = getViewportSize()
        if (size.width <= 0f || size.height <= 0f) return
        val region = getNavigator().getAction(Offset(centroid.x / size.width, centroid.y / size.height))
        when (region) {
            NavigationRegion.NEXT -> onNextFolder()
            NavigationRegion.PREV -> onPrevFolder()
            NavigationRegion.RIGHT -> if (isRtl) onPrevFolder() else onNextFolder()
            NavigationRegion.LEFT -> if (isRtl) onNextFolder() else onPrevFolder()
            // Same center zone as single-tap menu / seekbar chrome.
            NavigationRegion.MENU -> onBack()
        }
    }
}

/** Default zoom-only listener (no folder navigation). */
object DoubleTapZoom : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        if (!Settings.doubleTapToZoom.value) return
        val zoomFraction = state.zoomFraction ?: return
        if (zoomFraction > 0.05f) {
            state.resetZoom()
        } else {
            state.zoomTo(
                zoomFactor = state.contentTransformation.scaleMetadata.initialScale.scaleX * 2f,
                centroid = centroid,
            )
        }
    }
}
