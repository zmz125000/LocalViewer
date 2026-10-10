package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import com.hippo.ehviewer.image.HI_RES_PREVIEW_EDGE
import kotlin.math.abs
import me.saket.telephoto.zoomable.ZoomableState

internal val LocalReaderDrawScale = compositionLocalOf<() -> Float?> { { 1f } }

/**
 * True when a pinch, pan, or zoom animation is not running.
 * The scaler kernel bakes on this edge instead of on every zoom frame.
 */
internal val LocalReaderZoomSettled = compositionLocalOf<() -> Boolean> { { true } }

/** Graphics-layer scale applied by telephoto, or null until the gesture state exists. */
internal fun ZoomableState.readerDrawScaleOrNull(): Float? {
    val transformation = contentTransformation
    if (!transformation.isSpecified) return null
    val scale = transformation.scale.scaleX
    return scale.takeIf { it.isFinite() && it > 0f }
}

/** False while telephoto is animating a fling, rubber-band, or double-tap zoom. */
internal fun ZoomableState.readerZoomSettled(): Boolean = !isAnimationRunning

@Composable
internal fun readerDrawScaleProvider(state: ZoomableState, content: @Composable () -> Unit) {
    val scale = remember(state) { { state.readerDrawScaleOrNull() } }
    val settled = remember(state) { { state.readerZoomSettled() } }
    CompositionLocalProvider(
        LocalReaderDrawScale provides scale,
        LocalReaderZoomSettled provides settled,
        content = content,
    )
}

/** True when the fitted image, times the current zoom, is larger than the preview edge. */
internal fun zoomPastHiResPreview(
    destLongPx: Float,
    layerScale: Float,
    limitPx: Int = HI_RES_PREVIEW_EDGE,
): Boolean {
    if (destLongPx <= 0f || limitPx <= 0) return false
    if (!layerScale.isFinite() || layerScale <= 0f) return false
    return destLongPx * layerScale > limitPx.toFloat()
}

/**
 * 4096 px and file-resolution decodes of one photo share an aspect.
 * Telephoto must keep the first content size or the zoom gesture is cancelled
 * and the pager turns the page.
 */
internal fun Size.keepsZoomContent(previous: Size): Boolean {
    if (width <= 0f || height <= 0f || previous.width <= 0f || previous.height <= 0f) return false
    return abs(width / height - previous.width / previous.height) < 0.002f
}
