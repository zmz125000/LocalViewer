package com.hippo.ehviewer.ui.reader

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.gallery.statusObserved
import kotlin.math.ceil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace

/** One playback interval for countdown or scroll completion; only the active slot starts it. */
class SlideshowAutoScrollState {
    var readyPage by mutableStateOf<Int?>(null)
    var interval by mutableStateOf<SlideshowScrollInterval?>(null)
        private set

    private var finishedPage: Int? = null

    fun finishPlayback(page: Int) {
        finishedPage = page
    }

    fun startInterval(page: Int, seconds: Int, pixelsPerSecond: Int = 0): SlideshowScrollInterval? {
        if (finishedPage == page) return null
        finishedPage = null
        return SlideshowScrollInterval(
            page = page,
            startedAt = SystemClock.elapsedRealtime(),
            durationMillis = seconds.coerceIn(3, 200) * 1000,
            pixelsPerSecond = pixelsPerSecond.coerceIn(0, 1000),
        ).also { interval = it }
    }
}

class SlideshowScrollInterval(
    val page: Int,
    val startedAt: Long,
    val durationMillis: Int,
    val pixelsPerSecond: Int,
) {
    val scrollFinished = CompletableDeferred<Unit>()
}

private val SlideshowScrollEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private object SlideshowScrollDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

@Composable
private fun slideshowPagesDisplayed(pages: List<Page>): Boolean = pages.map { page ->
    key(page) {
        val status = page.statusObserved
        val displayed by page.displayReadyFlow.collectAsState()
        when (status) {
            is PageStatus.Ready -> displayed
            is PageStatus.Error, is PageStatus.Blocked -> true
            else -> false
        }
    }
}.let { it.isNotEmpty() && it.all { displayed -> displayed } }

@Composable
internal fun SlideshowAutoScroll(
    state: SlideshowAutoScrollState?,
    slot: Int,
    pager: PagerState,
    pages: List<Page>,
    zoomableState: ZoomableState,
    viewport: Size,
    configured: Boolean,
) {
    if (state == null) return
    DisposableEffect(state, slot) {
        onDispose { if (state.readyPage == slot) state.readyPage = null }
    }
    val displayed = slideshowPagesDisplayed(pages)
    val active = pager.settledPage == slot && !pager.isScrollInProgress && !PagerTransitionHandlers.isRunning(pager)
    val failed = pages.any { it.status is PageStatus.Error || it.status is PageStatus.Blocked }
    val ready = active && displayed && (configured || failed)
    LaunchedEffect(state, slot, ready) {
        if (ready) {
            state.readyPage = slot
        } else if (state.readyPage == slot) {
            state.readyPage = null
        }
    }
    // Reset cached slots after the outgoing transition finishes, before they are shown again.
    val transitioning = pager.isScrollInProgress || PagerTransitionHandlers.isRunning(pager)
    LaunchedEffect(state, slot, configured, pager.settledPage, transitioning, viewport) {
        if (configured && pager.settledPage != slot && !transitioning) {
            zoomableState.panToReadingStart(viewport, AbsoluteAlignment.Left)
        }
    }
    val interval = state.interval
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, interval, ready, configured, failed, viewport, lifecycleOwner) {
        if (!ready || interval == null || interval.page != slot || (!configured && !failed)) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val speed = interval.pixelsPerSecond
            if (speed == 0 && SystemClock.elapsedRealtime() - interval.startedAt >= interval.durationMillis) return@repeatOnLifecycle
            if (failed) {
                // Terminal errors cannot pan, but must not stall scroll-driven playback.
                withFrameNanos { }
                interval.scrollFinished.complete(Unit)
                return@repeatOnLifecycle
            }
            // A one-photo repeat and a resumed reader also start from the top/left.
            coroutineScope {
                launch { zoomableState.panToReadingStart(viewport, AbsoluteAlignment.Left) }.join()
            }
            withFrameNanos { }
            do {
                val bounds = with(zoomableState.coordinateSystem) { contentBounds(false).rectIn(CoordinateSpace.Viewport) }
                val delta = Offset(
                    x = (viewport.width - bounds.right).coerceAtMost(0f),
                    y = (viewport.height - bounds.bottom).coerceAtMost(0f),
                )
                if (delta.getDistance() <= 0.5f) break
                val duration = if (speed > 0) {
                    // Average rendered-pixel speed, retaining gentle acceleration/deceleration.
                    ceil(delta.getDistance().toDouble() * 1000.0 / speed).coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt()
                } else {
                    (interval.durationMillis - (SystemClock.elapsedRealtime() - interval.startedAt))
                        .coerceIn(0L, interval.durationMillis.toLong()).toInt()
                }
                if (duration <= 0) break
                // A gesture may cancel this child pan. Keep completion pending and resume from
                // the user's position once the gesture releases, unless playback itself stopped.
                coroutineScope {
                    val pan = launch {
                        withContext(SlideshowScrollDurationScale) {
                            zoomableState.panBy(delta, tween(duration, easing = SlideshowScrollEasing))
                        }
                    }
                    pan.join()
                }
                if (speed == 0) break
                // Recheck actual bounds: interruption must never count as reaching the end.
                withFrameNanos { }
            } while (true)
            interval.scrollFinished.complete(Unit)
        }
    }
}
