package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.hippo.ehviewer.image.HI_RES_PREVIEW_EDGE
import me.saket.telephoto.zoomable.ZoomableState

internal val LocalReaderDrawScale = compositionLocalOf<() -> Float?> { { 1f } }

/** Graphics-layer scale applied by telephoto, or null until the gesture state exists. */
internal fun ZoomableState.readerDrawScaleOrNull(): Float? {
    val transformation = contentTransformation
    if (!transformation.isSpecified) return null
    val scale = transformation.scale.scaleX
    return scale.takeIf { it.isFinite() && it > 0f }
}

@Composable
internal fun readerDrawScaleProvider(state: ZoomableState, content: @Composable () -> Unit) {
    val scale = remember(state) { { state.readerDrawScaleOrNull() } }
    CompositionLocalProvider(LocalReaderDrawScale provides scale, content = content)
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
