package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.core.snap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace

/** Align the actual zoomed bounds, rather than only changing the fitted image's base alignment. */
internal fun readerZoomStartPan(bounds: Rect, viewport: Size, alignment: Alignment.Horizontal): Offset {
    if (viewport.width <= 0f || viewport.height <= 0f || !viewport.width.isFinite() || !viewport.height.isFinite() ||
        bounds.width <= 0f || bounds.height <= 0f || !bounds.left.isFinite() || !bounds.top.isFinite() ||
        !bounds.width.isFinite() || !bounds.height.isFinite()
    ) {
        return Offset.Zero
    }
    val left = if (bounds.width > viewport.width) {
        alignment.align(bounds.width.roundToInt(), viewport.width.roundToInt(), LayoutDirection.Ltr).toFloat()
    } else {
        (viewport.width - bounds.width) / 2f
    }
    val top = if (bounds.height > viewport.height) 0f else (viewport.height - bounds.height) / 2f
    return Offset(left - bounds.left, top - bounds.top)
}

private suspend fun ZoomableState.panToReadingStart(viewport: Size, alignment: Alignment.Horizontal) {
    snapshotFlow { contentTransformation.isSpecified }.first { it }
    // Content and viewport measurements must settle before reading their transformed bounds.
    withFrameNanos { }
    val bounds = with(coordinateSystem) { contentBounds(false).rectIn(CoordinateSpace.Viewport) }
    val delta = readerZoomStartPan(bounds, viewport, alignment)
    if (delta.getDistance() > 0.5f) panBy(delta, animationSpec = snap())
}

internal suspend fun ZoomableState.configurePagerZoom(
    size: Size,
    viewport: Size,
    scale: ContentScale,
    alignment: Alignment.Horizontal,
    autoLandscapeZoom: Boolean,
) {
    contentScale = scale
    setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(size))
    applyPagerContentAlignment(size, scale, viewport, alignment)
    panToReadingStart(viewport, alignment)
    if (!autoLandscapeZoom) return
    val initialZoom = snapshotFlow { zoomFraction }.first { it != null }
    if (initialZoom != 0f) return
    delay(500)
    // A user pinch/pan during the delay takes precedence over automatic landscape zoom.
    if (zoomFraction != 0f || isAnimationRunning) return
    val fitted = with(coordinateSystem) { unscaledContentBounds(false).sizeIn(CoordinateSpace.Viewport) }
    if (fitted.width <= 0f || fitted.height <= 0f) return
    val target = ContentScale.FillHeight.computeScaleFactor(fitted, viewport).scaleX
        .coerceAtMost(zoomSpec.maximum.factor)
    if (!target.isFinite() || target <= 0f) return
    val anchorX = alignment.align(0, viewport.width.roundToInt(), LayoutDirection.Ltr).toFloat()
    zoomTo(target, Offset(anchorX, viewport.height / 2f))
    panToReadingStart(viewport, alignment)
}
