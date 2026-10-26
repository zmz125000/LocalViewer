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
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace

/** One clock for the photo countdown and its pan; prefetched/outgoing slots never start it. */
class SlideshowAutoScrollState {
    var readyPage by mutableStateOf<Int?>(null)
    var interval by mutableStateOf<SlideshowScrollInterval?>(null)
        private set

    private var finishedPage: Int? = null

    fun finishPlayback(page: Int) {
        finishedPage = page
    }

    fun startInterval(page: Int, seconds: Int) {
        if (finishedPage == page) return
        finishedPage = null
        interval = SlideshowScrollInterval(page, SystemClock.elapsedRealtime(), seconds.coerceIn(3, 200) * 1000)
    }
}

data class SlideshowScrollInterval(val page: Int, val startedAt: Long, val durationMillis: Int)

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
        if (!ready || !configured || interval == null || interval.page != slot || failed) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (SystemClock.elapsedRealtime() - interval.startedAt >= interval.durationMillis) return@repeatOnLifecycle
            // A one-photo repeat and a resumed reader also start from the top/left.
            zoomableState.panToReadingStart(viewport, AbsoluteAlignment.Left)
            withFrameNanos { }
            val bounds = with(zoomableState.coordinateSystem) { contentBounds(false).rectIn(CoordinateSpace.Viewport) }
            val delta = Offset(
                x = (viewport.width - bounds.right).coerceAtMost(0f),
                y = (viewport.height - bounds.bottom).coerceAtMost(0f),
            )
            val remaining = (interval.durationMillis - (SystemClock.elapsedRealtime() - interval.startedAt))
                .coerceIn(0L, interval.durationMillis.toLong()).toInt()
            if (remaining > 0 && delta.getDistance() > 0.5f) {
                // Match the photo's wall-clock duration regardless of the system animation scale.
                withContext(SlideshowScrollDurationScale) {
                    zoomableState.panBy(delta, tween(remaining, easing = SlideshowScrollEasing))
                }
            }
        }
    }
}
