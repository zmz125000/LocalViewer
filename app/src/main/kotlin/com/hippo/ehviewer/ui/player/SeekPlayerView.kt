package com.hippo.ehviewer.ui.player

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.hippo.ehviewer.R
import kotlin.math.abs

/**
 * Stock [PlayerView] plus surface gestures: tap toggles chrome, double-tap play/pause,
 * horizontal drag seeks (rate-limited, one minute per screen width). Touches on the
 * visible bottom bar go to Media3.
 *
 * Media3's built-in chrome animation slides the bottom bar; we disable it and fade alpha
 * instead. Auto-hide is also owned here so the fade path is used (Media3's timeout would
 * snap visibility off when its animation is disabled).
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

    /** Track-popup taps must not start the auto-hide, or the window loses its anchor. */
    private var holdChrome = false

    /** Caller-facing auto-hide timeout; Media3's own timer is kept at 0 (see [setControllerShowTimeoutMs]). */
    private var autoHideTimeoutMs = 0
    private var hiding = false

    private val autoHideRunnable = Runnable {
        if (isControllerFullyVisible && !hiding) {
            hideController()
        }
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            onPlaybackUiChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            onPlaybackUiChanged()
        }
    }

    init {
        // Media3's default chrome animation slides the bottom bar. Fade instead.
        setControllerAnimationEnabled(false)
        // Stock layout lifts exo_progress by exo_styled_progress_margin_bottom (52dp) so it
        // clears a separate bottom bar. That margin is reapplied on controller width changes.
        // Our clocks sit beside the bar, so a non-zero margin stretches the row.
        findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@addOnLayoutChangeListener
            if (lp.bottomMargin != 0) {
                lp.bottomMargin = 0
                v.layoutParams = lp
            }
        }
        findViewById<View>(R.id.video_controls_buttons)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            balanceTrackSpacer()
        }
    }

    private val controllerView: View?
        get() = findViewById(androidx.media3.ui.R.id.exo_controller)

    /**
     * Store the desired auto-hide timeout but leave Media3 at 0 ms so its layout manager
     * never snap-hides. We schedule [autoHideRunnable] ourselves and fade via [hideController].
     */
    override fun setControllerShowTimeoutMs(controllerShowTimeoutMs: Int) {
        autoHideTimeoutMs = controllerShowTimeoutMs
        super.setControllerShowTimeoutMs(0)
        if (isControllerFullyVisible) {
            scheduleAutoHide()
        }
    }

    override fun getControllerShowTimeoutMs(): Int = autoHideTimeoutMs

    override fun setPlayer(player: Player?) {
        getPlayer()?.removeListener(playbackListener)
        super.setPlayer(player)
        player?.addListener(playbackListener)
        onPlaybackUiChanged()
    }

    override fun showController() {
        hiding = false
        removeCallbacks(autoHideRunnable)
        val bar = controllerView
        bar?.animate()?.cancel()
        val alreadyShown = bar != null && bar.visibility == VISIBLE && bar.alpha >= 0.99f
        if (alreadyShown) {
            super.showController()
            scheduleAutoHide()
            return
        }
        bar?.alpha = 0f
        super.showController()
        bar?.animate()?.alpha(1f)?.setDuration(FADE_MS)?.start()
        scheduleAutoHide()
    }

    override fun hideController() {
        removeCallbacks(autoHideRunnable)
        val bar = controllerView
        if (bar == null || bar.visibility != VISIBLE || hiding) {
            hiding = false
            super.hideController()
            return
        }
        hiding = true
        bar.animate().cancel()
        bar.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                hiding = false
                super.hideController()
                // Reset so the next show starts from a clean fully-opaque controller.
                bar.alpha = 1f
            }
            .start()
    }

    private fun scheduleAutoHide() {
        removeCallbacks(autoHideRunnable)
        if (holdChrome || autoHideTimeoutMs <= 0 || hiding) return
        if (!shouldAutoHide()) return
        postDelayed(autoHideRunnable, autoHideTimeoutMs.toLong())
    }

    /**
     * Give the transport cluster a matching gap on the right when the row can hold
     * track buttons + cluster + the same gap. Otherwise drop the gap so the buttons fit.
     */
    private fun balanceTrackSpacer() {
        val tracks = findViewById<View>(R.id.video_track_buttons) ?: return
        val transport = findViewById<View>(R.id.video_transport) ?: return
        val spacer = findViewById<View>(R.id.video_track_balance) ?: return
        val row = tracks.parent as? View ?: return
        if (tracks.width == 0 || transport.width == 0 || row.width == 0) return
        val available = row.width - row.paddingLeft - row.paddingRight
        val want = if (available >= tracks.width * 2 + transport.width) tracks.width else 0
        if (spacer.layoutParams.width == want) return
        spacer.layoutParams = spacer.layoutParams.apply { width = want }
    }

    private fun touchHitsTrackButton(event: MotionEvent): Boolean {
        val audio = findViewById<View>(androidx.media3.ui.R.id.exo_audio_track)
        val subtitle = findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
        return hitsView(audio, event) || hitsView(subtitle, event)
    }

    private fun hitsView(view: View?, event: MotionEvent): Boolean {
        if (view == null || view.visibility != VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX
        val y = event.rawY
        return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
    }

    /** Match Media3: keep chrome up while paused / idle / ended. */
    private fun shouldAutoHide(): Boolean {
        val current = player ?: return false
        val state = current.playbackState
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return false
        return current.playWhenReady
    }

    private fun onPlaybackUiChanged() {
        if (!isControllerFullyVisible || hiding) return
        if (shouldAutoHide()) {
            scheduleAutoHide()
        } else {
            removeCallbacks(autoHideRunnable)
        }
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
                    // Hold chrome (if any) while scrubbing.
                    removeCallbacks(autoHideRunnable)
                }
                val duration = current.duration
                if (duration <= 0L || duration == C.TIME_UNSET) return true
                val target = (
                    seekStartMs + scrubSeekDeltaMs(totalX, width, duration)
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
            // Subtitle / audio open a PopupWindow. Keep the bar up until some other touch.
            holdChrome = controllerGesture && touchHitsTrackButton(event)
            if (controllerGesture) {
                // User is interacting with the bar — defer auto-hide.
                removeCallbacks(autoHideRunnable)
            }
        }
        if (controllerGesture) {
            val handled = super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleAutoHide()
            }
            return handled
        }

        val handled = gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSeeking = seeking
                if (seeking && lastSeekMs != C.TIME_UNSET) {
                    player?.seekTo(lastSeekMs)
                }
                seeking = false
                lastSeekMs = C.TIME_UNSET
                if (wasSeeking) {
                    if (isControllerFullyVisible) scheduleAutoHide()
                    return true
                }
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
        private const val SCRUB_INTERVAL_MS = 120L
        private const val FADE_MS = 200L
    }
}

/**
 * Horizontal scrub distance mapped to a seek delta.
 * A full screen width moves at most [SCRUB_WINDOW_MS], so a short drag does not jump minutes.
 */
internal fun scrubSeekDeltaMs(
    dragXPx: Float,
    viewWidthPx: Int,
    durationMs: Long,
    maxWindowMs: Long = SCRUB_WINDOW_MS,
): Long {
    if (durationMs <= 0L || viewWidthPx <= 0) return 0L
    val window = minOf(durationMs, maxWindowMs)
    return (dragXPx / viewWidthPx.toFloat() * window).toLong()
}

/** One full-width drag. Was 10 minutes, which made a small movement jump by a large step. */
internal const val SCRUB_WINDOW_MS = 2L * 60L * 1000L
