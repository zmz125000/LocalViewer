package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState

internal val PRESS_HOLD_ZOOM_VALUES = listOf(0, 110, 120, 130, 140, 150)

private const val PRESS_HOLD_ZOOM_IN_DURATION_MS = 120

internal fun pressHoldZoomFactor(prefValue: Int): Float = if (prefValue > 100) prefValue / 100f else 0f

internal fun pressHoldZoomTimeoutMillis(longPressTimeoutMillis: Long): Long = (longPressTimeoutMillis / 2L).coerceAtLeast(1L)

internal fun isZoomedForPressHold(currentScale: Float, initialScale: Float): Boolean = initialScale > 0f && currentScale.isFinite() && initialScale.isFinite() && currentScale / initialScale > 1.01f

@Stable
class PressHoldZoomController internal constructor(
    private val zoomableState: ZoomableState,
    private val scope: CoroutineScope,
    private val getFactor: () -> Float,
    private val getToggle: () -> Boolean,
) {
    private var zoomJob: Job? = null
    private var panChannel: Channel<Offset>? = null

    internal fun onToggleLongPress(centroid: Offset) {
        val factor = getFactor()
        if (factor <= 0f) return
        val transformation = zoomableState.contentTransformation
        if (!transformation.isSpecified || zoomableState.zoomFraction == null) return
        val initialScale = transformation.scaleMetadata.initialScale.scaleX
        if (initialScale <= 0f || !initialScale.isFinite()) return

        if (isZoomedForPressHold(transformation.scale.scaleX, initialScale)) {
            launchResetZoom()
        } else {
            panChannel?.close()
            panChannel = null
            zoomJob?.cancel()
            zoomJob = scope.launch {
                zoomableState.zoomTo(
                    zoomFactor = initialScale * factor,
                    centroid = centroid,
                    animationSpec = tween(durationMillis = PRESS_HOLD_ZOOM_IN_DURATION_MS),
                )
            }
        }
    }

    internal fun onHoldStart(centroid: Offset): Boolean {
        val factor = getFactor()
        if (factor <= 0f) return false
        val transformation = zoomableState.contentTransformation
        if (!transformation.isSpecified || zoomableState.zoomFraction == null) return false
        val initialScale = transformation.scaleMetadata.initialScale.scaleX
        if (initialScale <= 0f || !initialScale.isFinite()) return false

        panChannel?.close()
        val channel = Channel<Offset>(Channel.UNLIMITED)
        panChannel = channel
        zoomJob?.cancel()
        zoomJob = scope.launch {
            zoomableState.zoomTo(
                zoomFactor = initialScale * factor,
                centroid = centroid,
                animationSpec = tween(durationMillis = PRESS_HOLD_ZOOM_IN_DURATION_MS),
            )
            // Drain any drag deltas accumulated during zoom-in, then stream live pan deltas.
            for (first in channel) {
                var total = first
                while (true) {
                    val more = channel.tryReceive().getOrNull() ?: break
                    total += more
                }
                if (total != Offset.Zero) {
                    zoomableState.panBy(total, animationSpec = snap())
                }
            }
        }
        return true
    }

    internal fun onHoldDrag(delta: Offset) {
        if (delta != Offset.Zero) {
            panChannel?.trySend(delta)
        }
    }

    internal fun onHoldHandOffToPinch() {
        panChannel?.close()
        panChannel = null
    }

    internal fun onHoldRelease() {
        launchResetZoom()
    }

    internal fun isToggleMode(): Boolean = getToggle()

    private fun launchResetZoom() {
        panChannel?.close()
        panChannel = null
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
    val controller = remember(zoomableState, scope) {
        PressHoldZoomController(
            zoomableState = zoomableState,
            scope = scope,
            getFactor = { currentFactor },
            getToggle = { currentToggle },
        )
    }
    val enabled = factor > 0f
    // When enabled, press-and-hold is handled at half the standard timeout by [releaseModifier].
    val onLongClick: ((Offset) -> Unit)? = if (enabled) null else onFallbackLongClick
    val gestureModifier = if (enabled) {
        Modifier.pointerInput(controller) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val timeoutMs = pressHoldZoomTimeoutMillis(viewConfiguration.longPressTimeoutMillis)
                var currentPosition = down.position
                val timedOut = withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) return@withTimeoutOrNull false
                        currentPosition = change.position
                        if ((currentPosition - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } == null
                if (!timedOut) return@awaitEachGesture

                if (controller.isToggleMode()) {
                    controller.onToggleLongPress(currentPosition)
                    // Consume until release so lifting the finger does not trigger a tap.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                } else {
                    if (!controller.onHoldStart(currentPosition)) return@awaitEachGesture
                    var lastPosition = currentPosition
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            // User added a second finger to pinch; hand control over without snapping back.
                            controller.onHoldHandOffToPinch()
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            change?.consume()
                            controller.onHoldRelease()
                            break
                        }
                        val delta = change.position - lastPosition
                        lastPosition = change.position
                        change.consume()
                        if (delta != Offset.Zero) {
                            controller.onHoldDrag(delta)
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }
    return PressHoldZoomBinding(
        enabled = enabled,
        onLongClick = onLongClick,
        releaseModifier = gestureModifier,
    )
}

@Stable
data class PressHoldZoomBinding(
    val enabled: Boolean,
    val onLongClick: ((Offset) -> Unit)?,
    val releaseModifier: Modifier,
)
