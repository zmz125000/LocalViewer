package com.hippo.ehviewer.ui.reader

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Mouse-wheel lines along the reading axis. Positive is wheel-down (next).
 * A horizontal wheel is used when it is the larger component.
 */
internal fun readingWheelLines(x: Float, y: Float): Float = if (abs(y) >= abs(x)) y else x

/** Turns fractional wheel motion into whole page steps, keeping the remainder. */
internal class WheelNotches {
    private var pending = 0f

    fun push(lines: Float): Int {
        if (lines == 0f) return 0
        pending += lines
        val steps = pending.toInt()
        if (steps == 0) return 0
        pending -= steps.toFloat()
        return steps
    }
}

/**
 * One wheel notch moves one page. Ctrl/Meta+wheel is left for zoom.
 * The event is consumed so the pager does not also nudge by a few pixels.
 */
@Composable
fun Modifier.readerMouseWheelPages(onStep: suspend (forward: Boolean) -> Unit): Modifier {
    val notches = remember { WheelNotches() }
    val channel = remember { Channel<Boolean>(capacity = Channel.BUFFERED) }
    val step by rememberUpdatedState(onStep)
    LaunchedEffect(channel) {
        for (forward in channel) step(forward)
    }
    return readerMouseWheel { delta ->
        val steps = notches.push(readingWheelLines(delta.x, delta.y))
        if (steps > 0) {
            repeat(steps) { channel.trySend(true) }
        } else if (steps < 0) {
            repeat(-steps) { channel.trySend(false) }
        }
    }
}

/** Wheel scrolls a continuous strip. Vertical wheel drives a horizontal strip too. */
@Composable
fun Modifier.readerMouseWheelList(state: LazyListState): Modifier {
    val context = LocalContext.current
    val pxPerLine = remember(context) {
        ViewConfiguration.get(context).scaledVerticalScrollFactor
    }
    val scope = rememberCoroutineScope()
    val list by rememberUpdatedState(state)
    return readerMouseWheel { delta ->
        val lines = readingWheelLines(delta.x, delta.y)
        if (lines != 0f) {
            scope.launch { list.scrollBy(lines * pxPerLine) }
        }
    }
}

@Composable
private fun Modifier.readerMouseWheel(onWheel: (Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(onWheel)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val mods = event.keyboardModifiers
                if (mods.isCtrlPressed || mods.isMetaPressed) continue
                if (event.changes.all { it.isConsumed }) continue
                var x = 0f
                var y = 0f
                for (change in event.changes) {
                    val scroll = change.scrollDelta
                    x += scroll.x
                    y += scroll.y
                }
                if (x == 0f && y == 0f) continue
                event.changes.forEach { change ->
                    if (!change.isConsumed) change.consume()
                }
                current(Offset(x, y))
            }
        }
    }
}
