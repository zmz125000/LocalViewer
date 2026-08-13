package com.hippo.ehviewer.ui.player

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Stock [PlayerView] plus surface gestures: tap toggles chrome, double-tap play/pause,
 * horizontal drag seeks (rate-limited). Touches on the visible bottom bar go to Media3.
 */
@UnstableApi
class SeekPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scrubStartPx = maxOf(touchSlop * 3f, 32f * resources.displayMetrics.density)
    private var downX = 0f
    private var downY = 0f
    private var seekStartMs = 0L
    private var seeking = false
    private var lastSeekMs = C.TIME_UNSET
    private var lastSeekAt = 0L
    private var controllerGesture = false
    private var shownOnThisTap = false

    init {
        // Media3's default chrome animation slides the bottom bar. Fade instead.
        setControllerAnimationEnabled(false)
    }

    private val controllerView: View?
        get() = findViewById(androidx.media3.ui.R.id.exo_controller)

    override fun showController() {
        val bar = controllerView
        bar?.animate()?.cancel()
        val alreadyShown = bar != null && bar.visibility == VISIBLE && bar.alpha >= 0.99f
        if (alreadyShown) {
            super.showController()
            return
        }
        bar?.alpha = 0f
        super.showController()
        bar?.animate()?.alpha(1f)?.setDuration(FADE_MS)?.start()
    }

    override fun hideController() {
        val bar = controllerView
        if (bar == null || bar.visibility != VISIBLE) {
            super.hideController()
            return
        }
        bar.animate().cancel()
        bar.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction { super.hideController() }
            .start()
    }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (seeking) return true
                // Show on first tap — do not wait for double-tap confirmation.
                if (!isControllerFullyVisible) {
                    showController()
                    shownOnThisTap = true
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (seeking) return true
                if (shownOnThisTap) {
                    shownOnThisTap = false
                    return true
                }
                if (isControllerFullyVisible) hideController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (seeking) return true
                val current = player ?: return true
                if (current.playbackState == Player.STATE_ENDED) {
                    current.seekTo(0L)
                    current.play()
                } else if (current.isPlaying) {
                    current.pause()
                } else {
                    current.play()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val current = player ?: return false
                val totalX = e2.x - downX
                val totalY = e2.y - downY
                if (!seeking) {
                    if (abs(totalX) < scrubStartPx || abs(totalX) <= abs(totalY) * 1.5f) return false
                    val duration = current.duration
                    if (duration <= 0L || duration == C.TIME_UNSET) return false
                    seeking = true
                    seekStartMs = current.currentPosition
                    lastSeekMs = C.TIME_UNSET
                    lastSeekAt = 0L
                }
                val duration = current.duration
                if (duration <= 0L || duration == C.TIME_UNSET) return true
                val window = minOf(duration, MAX_DRAG_WINDOW_MS)
                val target = (
                    seekStartMs + (totalX / width.coerceAtLeast(1).toFloat() * window).toLong()
                    ).coerceIn(0L, duration)
                dispatchSeek(current, target)
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            seeking = false
            lastSeekMs = C.TIME_UNSET
            lastSeekAt = 0L
            shownOnThisTap = false
            controllerGesture = isControllerFullyVisible &&
                event.y >= height - 132f * resources.displayMetrics.density
        }
        if (controllerGesture) return super.onTouchEvent(event)

        val handled = gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSeeking = seeking
                if (seeking && lastSeekMs != C.TIME_UNSET) {
                    player?.seekTo(lastSeekMs)
                }
                seeking = false
                lastSeekMs = C.TIME_UNSET
                if (wasSeeking) return true
            }
        }
        if (seeking) return true
        return handled || super.onTouchEvent(event)
    }

    private fun dispatchSeek(current: Player, targetMs: Long) {
        lastSeekMs = targetMs
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt < SCRUB_INTERVAL_MS) return
        lastSeekAt = now
        current.seekTo(targetMs)
    }

    companion object {
        private const val MAX_DRAG_WINDOW_MS = 10L * 60L * 1000L
        private const val SCRUB_INTERVAL_MS = 120L
        private const val FADE_MS = 200L
    }
}
