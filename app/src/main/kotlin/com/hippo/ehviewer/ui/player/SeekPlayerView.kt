package com.hippo.ehviewer.ui.player

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.CaptioningManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.PopupWindow
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import com.ehviewer.core.i18n.R as I18nR
import com.hippo.ehviewer.R
import kotlin.math.abs

/**
 * Stock [PlayerView] plus surface gestures: tap toggles chrome, double-tap closes the player,
 * horizontal drag seeks (rate-limited: one minute per screen width in portrait, two in
 * landscape). Touches on the
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
    /** Double-tap on the video. The activity finishes so playback and the window both go away. */
    var onClosePlayer: (() -> Unit)? = null

    /** Vertical swipe while [scrollToNextEnabled]: +1 next (up), -1 previous (down). */
    var onPlaylistScroll: ((Int) -> Unit)? = null

    /** Shorts-style swipe between playlist items. Horizontal scrub stays available. */
    var scrollToNextEnabled: Boolean = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scrubStartPx = maxOf(touchSlop * 3f, 32f * resources.displayMetrics.density)
    private var downX = 0f
    private var downY = 0f
    private var seekStartMs = 0L
    private var seeking = false
    private var scrollingNext = false
    private var lastSeekMs = C.TIME_UNSET
    private var lastSeekAt = 0L
    private var controllerGesture = false
    private var shownOnThisTap = false

    private var controlMenu: PopupWindow? = null

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

        override fun onTracksChanged(tracks: Tracks) {
            updateAudioTrackButton()
            post { updateAudioTrackButton() }
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
        for (id in intArrayOf(R.id.video_controls_buttons, R.id.video_track_buttons, R.id.video_extra_buttons)) {
            findViewById<View>(id)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                layoutControlRows()
            }
        }
        findViewById<View>(androidx.media3.ui.R.id.exo_audio_track)?.setOnClickListener {
            showTrackMenu(it, C.TRACK_TYPE_AUDIO)
        }
        findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener {
            showTrackMenu(it, C.TRACK_TYPE_TEXT)
        }
        subtitleView?.let { subtitles ->
            val captions = context.getSystemService(CaptioningManager::class.java)
            subtitles.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (subtitles.width <= 0 || subtitles.height <= 0) return@addOnLayoutChangeListener
                val scale = if (captions?.isEnabled == true) captions.fontScale else 1f
                // Size plain text to the video bounds and cap the default size on large displays.
                val textSizePx = minOf(
                    subtitles.width * 0.042f,
                    subtitles.height * 0.075f,
                    24f * resources.displayMetrics.density,
                )
                subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * scale)
            }
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
        updateAudioTrackButton()
        post { updateAudioTrackButton() }
    }

    /** No audio track, or only one, has nothing to pick. Leave the button visible and grey. */
    private fun updateAudioTrackButton() {
        val button = findViewById<View>(androidx.media3.ui.R.id.exo_audio_track) ?: return
        val tracks = player?.currentTracks
        var count = 0
        if (tracks != null) {
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (index in 0 until group.length) {
                    if (group.isTrackSupported(index)) count++
                }
            }
        }
        val enabled = count > 1
        button.visibility = VISIBLE
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    private fun showTrackMenu(anchor: View, trackType: Int) {
        val current = player ?: return
        if (!current.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val isSubtitle = trackType == C.TRACK_TYPE_TEXT
        val nameProvider = DefaultTrackNameProvider(resources)
        val labels = mutableListOf(
            context.getString(
                if (isSubtitle) androidx.media3.ui.R.string.exo_track_selection_none else I18nR.string.video_audio_auto,
            ),
        )
        val overrides = mutableListOf<TrackSelectionOverride>()
        var selected = 0
        for (group in current.currentTracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val codec = if (isSubtitle) null else audioCodecLabel(format)
                val name = if (isSubtitle) {
                    format.label?.takeIf { it.isNotBlank() } ?: nameProvider.getTrackName(format)
                } else {
                    nameProvider.getTrackName(format)
                }
                labels += name + (codec?.let { " ($it)" } ?: "")
                overrides += TrackSelectionOverride(group.mediaTrackGroup, index)
                if (group.isTrackSelected(index) &&
                    (isSubtitle || current.trackSelectionParameters.overrides.containsKey(group.mediaTrackGroup))
                ) {
                    selected = labels.lastIndex
                }
            }
        }
        showControlMenu(anchor, labels, selected) { choice ->
            if (player === current) {
                val parameters = current.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(trackType)
                if (choice > 0) {
                    parameters.setOverrideForType(overrides[choice - 1])
                } else if (isSubtitle) {
                    // Match Media3's None option, which still permits forced subtitles.
                    parameters.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                        .setPreferredTextLanguage(null)
                        .setPreferredTextRoleFlags(0)
                }
                current.trackSelectionParameters = parameters.build()
            }
        }
    }

    private fun audioCodecLabel(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS_UHD_P2 -> "DTS-UHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_AAC -> "AAC"
        else -> format.codecs?.takeIf { it.isNotBlank() }
            ?: format.sampleMimeType?.substringAfter('/')?.takeIf { it.isNotBlank() }
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
        if (controlMenu != null || autoHideTimeoutMs <= 0 || hiding) return
        if (!shouldAutoHide()) return
        postDelayed(autoHideRunnable, autoHideTimeoutMs.toLong())
    }

    /** Keep transport centered and place the side buttons on a second row when space is tight. */
    private fun layoutControlRows() {
        val row = findViewById<View>(R.id.video_controls_buttons) ?: return
        val tracks = findViewById<View>(R.id.video_track_buttons) ?: return
        val transport = findViewById<View>(R.id.video_transport) ?: return
        val actions = findViewById<View>(R.id.video_extra_buttons) ?: return
        val actionRow = findViewById<View>(R.id.video_action_row) ?: return
        if (transport.width == 0 || row.width == 0) return
        val available = row.width - row.paddingLeft - row.paddingRight
        val compact = available < transport.width + 2 * maxOf(tracks.width, actions.width)
        val height = transport.layoutParams.height + if (compact) actionRow.layoutParams.height else 0
        val gravity = if (compact) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        val actionParams = actionRow.layoutParams as FrameLayout.LayoutParams
        if (actionParams.gravity != gravity) {
            actionParams.gravity = gravity
            actionRow.layoutParams = actionParams
        }
        if (row.layoutParams.height != height) {
            row.layoutParams = row.layoutParams.apply { this.height = height }
        }
    }

    internal fun showControlMenu(anchor: View, labels: List<String>, selected: Int, onSelection: (Int) -> Unit) {
        if (!anchor.isAttachedToWindow) return
        controlMenu?.dismiss()

        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        if (frame.width() <= 2 * gap) return

        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, labels)
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackgroundFloating))
        val menuColor = colors.getColor(0, context.getColor(android.R.color.background_dark))
        colors.recycle()
        val shape = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(menuColor)
        }
        val list = ListView(context).apply {
            setAdapter(adapter)
            choiceMode = ListView.CHOICE_MODE_SINGLE
            divider = null
            setPadding(0, gap, 0, gap)
            background = shape
            clipToOutline = true
            if (selected in labels.indices) setItemChecked(selected, true)
        }

        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var contentWidth = (160 * density).toInt()
        for (index in labels.indices) {
            val item = adapter.getView(index, null, list)
            item.measure(unspecified, unspecified)
            contentWidth = maxOf(contentWidth, item.measuredWidth)
        }
        val popupWidth = contentWidth.coerceAtMost(minOf(frame.width() - 2 * gap, (360 * density).toInt()))
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val above = location[1] - frame.top - gap
        val below = frame.bottom - location[1] - anchor.height - gap
        val availableHeight = maxOf(above, below)
        if (availableHeight <= 0) return
        list.measure(
            MeasureSpec.makeMeasureSpec(popupWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST),
        )
        val popupHeight = list.measuredHeight
        val showAbove = popupHeight <= above
        val left = location[0].coerceIn(frame.left + gap, frame.right - gap - popupWidth)
        val top = if (showAbove) location[1] - popupHeight - gap else location[1] + anchor.height + gap
        val popup = PopupWindow(list, popupWidth, popupHeight, true).apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = shape.cornerRadius
                setColor(menuColor)
            })
            isOutsideTouchable = true
            setIsLaidOutInScreen(true)
            setIsClippedToScreen(true)
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 8 * density
            enterTransition = null
            exitTransition = null
            animationStyle = R.style.VideoControlMenuAnimation
        }
        list.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onSelection(position)
        }
        popup.setOnDismissListener {
            if (controlMenu === popup) controlMenu = null
            scheduleAutoHide()
        }
        controlMenu = popup
        showController()
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, left, top)
        list.post {
            if (popup.isShowing && selected > list.lastVisiblePosition) list.setSelection(selected)
        }
    }

    override fun onDetachedFromWindow() {
        controlMenu?.dismiss()
        removeCallbacks(autoHideRunnable)
        super.onDetachedFromWindow()
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
                onClosePlayer?.invoke()
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
                if (scrollingNext) return true
                if (!seeking && scrollToNextEnabled &&
                    abs(totalY) >= scrubStartPx && abs(totalY) > abs(totalX) * 1.5f
                ) {
                    scrollingNext = true
                    return true
                }
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
                    seekStartMs + scrubSeekDeltaMs(
                        totalX,
                        width,
                        duration,
                        scrubWindowMs(width, height),
                    )
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
            scrollingNext = false
            lastSeekMs = C.TIME_UNSET
            lastSeekAt = 0L
            shownOnThisTap = false
            controllerGesture = isControllerFullyVisible &&
                hitsView(findViewById(androidx.media3.ui.R.id.exo_bottom_bar), event)
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
                if (scrollingNext) {
                    val up = event.actionMasked == MotionEvent.ACTION_UP
                    val totalY = event.y - downY
                    scrollingNext = false
                    if (up && abs(totalY) >= scrubStartPx) {
                        onPlaylistScroll?.invoke(if (totalY < 0f) 1 else -1)
                    }
                    return true
                }
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
        private const val FADE_MS = 120L
    }
}

/**
 * Horizontal scrub distance mapped to a seek delta.
 * A full screen width moves at most [maxWindowMs]. Portrait uses one minute and landscape two,
 * so a short drag does not jump through the movie.
 */
internal fun scrubSeekDeltaMs(
    dragXPx: Float,
    viewWidthPx: Int,
    durationMs: Long,
    maxWindowMs: Long = SCRUB_WINDOW_PORTRAIT_MS,
): Long {
    if (durationMs <= 0L || viewWidthPx <= 0) return 0L
    val window = minOf(durationMs, maxWindowMs)
    return (dragXPx / viewWidthPx.toFloat() * window).toLong()
}

/** Full-width drag when the player is taller than it is wide. */
internal const val SCRUB_WINDOW_PORTRAIT_MS = 60L * 1000L

/** Full-width drag when the player is wider than it is tall, or square. */
internal const val SCRUB_WINDOW_LANDSCAPE_MS = 2L * 60L * 1000L

internal fun scrubWindowMs(viewWidthPx: Int, viewHeightPx: Int): Long = if (viewWidthPx >= viewHeightPx) SCRUB_WINDOW_LANDSCAPE_MS else SCRUB_WINDOW_PORTRAIT_MS
