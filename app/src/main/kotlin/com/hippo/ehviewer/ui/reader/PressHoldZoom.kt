package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState

internal val PRESS_HOLD_ZOOM_VALUES = listOf(0, 110, 120, 130, 140, 150)

internal fun pressHoldZoomFactor(prefValue: Int): Float = if (prefValue > 100) prefValue / 100f else 0f

internal fun isZoomedForPressHold(currentScale: Float, initialScale: Float): Boolean = initialScale > 0f && currentScale.isFinite() && initialScale.isFinite() && currentScale / initialScale > 1.01f

@Stable
class PressHoldZoomController internal constructor(
    private val zoomableState: ZoomableState,
    private val scope: CoroutineScope,
    private val getFactor: () -> Float,
    private val getToggle: () -> Boolean,
    private val getFallback: () -> ((Offset) -> Unit)?,
) {
    private var pointerDown = false
    private var holdZoomActive = false
    private var zoomJob: Job? = null

    val enabled: Boolean
        get() = getFactor() > 0f

    internal fun onPointerCountsChanged(pressedCount: Int) {
        val anyPressed = pressedCount > 0
        pointerDown = anyPressed
        if (!holdZoomActive) return
        if (pressedCount >= 2) {
            // User added a second finger to pinch; hand control over without snapping back.
            holdZoomActive = false
        } else if (!anyPressed) {
            holdZoomActive = false
            launchResetZoom()
        }
    }

    internal fun handleLongClick(centroid: Offset) {
        val factor = getFactor()
        if (factor <= 0f) {
            getFallback()?.invoke(centroid)
            return
        }
        val transformation = zoomableState.contentTransformation
        if (!transformation.isSpecified || zoomableState.zoomFraction == null) return
        val initialScale = transformation.scaleMetadata.initialScale.scaleX
        if (initialScale <= 0f || !initialScale.isFinite()) return

        if (getToggle()) {
            holdZoomActive = false
            if (isZoomedForPressHold(transformation.scale.scaleX, initialScale)) {
                launchResetZoom()
            } else {
                launchZoomTo(initialScale * factor, centroid)
            }
        } else {
            if (!pointerDown) return
            holdZoomActive = true
            launchZoomTo(initialScale * factor, centroid)
        }
    }

    private fun launchZoomTo(zoomFactor: Float, centroid: Offset) {
        zoomJob?.cancel()
        zoomJob = scope.launch {
            zoomableState.zoomTo(zoomFactor = zoomFactor, centroid = centroid)
        }
    }

    private fun launchResetZoom() {
        zoomJob?.cancel()
        zoomJob = scope.launch {
            zoomableState.resetZoom()
        }
    }
}

@Composable
fun rememberPressHoldZoom(
    zoomableState: ZoomableState,
    onFallbackLongClick: ((Offset) -> Unit)? = null,
): PressHoldZoomBinding {
    val scope = rememberCoroutineScope()
    val prefValue by Settings.pressHoldZoom.collectAsState()
    val toggle by Settings.pressHoldZoomToggle.collectAsState()
    val factor = pressHoldZoomFactor(prefValue)
    val currentFactor by rememberUpdatedState(factor)
    val currentToggle by rememberUpdatedState(toggle)
    val currentFallback by rememberUpdatedState(onFallbackLongClick)
    val controller = remember(zoomableState, scope) {
        PressHoldZoomController(
            zoomableState = zoomableState,
            scope = scope,
            getFactor = { currentFactor },
            getToggle = { currentToggle },
            getFallback = { currentFallback },
        )
    }
    val enabled = factor > 0f
    val onLongClick: ((Offset) -> Unit)? = when {
        enabled -> controller::handleLongClick
        onFallbackLongClick != null -> onFallbackLongClick
        else -> null
    }
    val releaseModifier = if (enabled && !toggle) {
        Modifier.pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    controller.onPointerCountsChanged(event.changes.count { it.pressed })
                }
            }
        }
    } else {
        Modifier
    }
    return PressHoldZoomBinding(
        enabled = enabled,
        onLongClick = onLongClick,
        releaseModifier = releaseModifier,
    )
}

@Stable
data class PressHoldZoomBinding(
    val enabled: Boolean,
    val onLongClick: ((Offset) -> Unit)?,
    val releaseModifier: Modifier,
)
