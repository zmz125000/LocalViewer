package com.hippo.ehviewer.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.CaptioningManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
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

    /** Vertical swipe: +1 next (up), -1 previous (down). */
    var onPlaylistScroll: ((Int) -> Unit)? = null

    /** Shorts-style swipe between playlist items. Horizontal scrub stays available. */
    var playlistSwipeMinutes = 0

    internal var onTrackSelectionChanged: ((Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeGuardPx = 32f * resources.displayMetrics.density
    private val scrubStartPx = maxOf(touchSlop * 3f, edgeGuardPx)
    private var downX = 0f
    private var downY = 0f
    private var seekStartMs = 0L
    private var seeking = false
    private var scrollingNext = false
    private var lastSeekMs = C.TIME_UNSET
    private var lastSeekAt = 0L
    private var controllerGesture = false
    private var shownOnThisTap = false
    private var canStartDrag = false

    private var controlMenu: PopupWindow? = null
    private val captions = context.getSystemService(CaptioningManager::class.java)
    private val captionListener = object : CaptioningManager.CaptioningChangeListener() {
        override fun onEnabledChanged(enabled: Boolean) = updateCaptionStyle()
        override fun onFontScaleChanged(fontScale: Float) = updateCaptionStyle()
        override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) = updateCaptionStyle()
    }

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
            updateSubtitleButton()
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
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layoutControls()
            layoutSubtitles()
        }
        findViewById<View>(R.id.video_controls_buttons)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layoutControls()
        }
        findViewById<View>(androidx.media3.ui.R.id.exo_audio_track)?.setOnClickListener {
            showTrackMenu(it, C.TRACK_TYPE_AUDIO)
        }
        findViewById<View>(R.id.video_subtitle)?.setOnClickListener {
            showTrackMenu(it, C.TRACK_TYPE_TEXT)
        }
        subtitleView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSubtitles() }
    }

    private fun layoutSubtitles() {
        val subtitles = subtitleView ?: return
        if (subtitles.width <= 0 || subtitles.height <= 0 || width <= 0 || height <= 0) return
        // The content frame can extend past the screen in crop mode. Keep captions in the visible part.
        val hiddenWidth = (subtitles.width - width).coerceAtLeast(0)
        val hiddenHeight = (subtitles.height - height).coerceAtLeast(0)
        subtitles.setPadding(hiddenWidth / 2, hiddenHeight / 2, (hiddenWidth + 1) / 2, (hiddenHeight + 1) / 2)
        val scale = if (captions?.isEnabled == true) captions.fontScale else 1f
        val textSizePx = minOf(
            (subtitles.width - hiddenWidth) * 0.042f,
            (subtitles.height - hiddenHeight) * 0.075f,
            24f * resources.displayMetrics.density,
        )
        subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * scale)
    }

    private fun updateCaptionStyle() {
        subtitleView?.setUserDefaultStyle()
        layoutSubtitles()
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
        updateSubtitleButton()
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

    private fun updateSubtitleButton() {
        val button = findViewById<ImageButton>(R.id.video_subtitle) ?: return
        val enabled = player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_TEXT) == true
        button.setImageResource(
            if (enabled) {
                androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_on
            } else {
                androidx.media3.ui.R.drawable.exo_styled_controls_subtitle_off
            },
        )
        button.stateDescription = context.getString(
            if (enabled) {
                androidx.media3.ui.R.string.exo_controls_cc_enabled_description
            } else {
                androidx.media3.ui.R.string.exo_controls_cc_disabled_description
            },
        )
    }

    private fun openCaptionPreferences() {
        try {
            context.startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, I18nR.string.browse_action_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    internal fun showTrackMenu(anchor: View, trackType: Int) {
        val isSubtitle = trackType == C.TRACK_TYPE_TEXT
        val captionPreferences = if (isSubtitle) context.getString(R.string.video_caption_preferences) else null
        val current = player
        if (current == null || !current.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
            if (captionPreferences != null) showControlMenu(anchor, listOf(captionPreferences), -1) { openCaptionPreferences() }
            return
        }
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
        showControlMenu(anchor, labels, selected, captionPreferences) { choice ->
            if (choice == labels.size) {
                openCaptionPreferences()
            } else if (player === current) {
                val parameters = current.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(trackType)
                if (choice > 0) {
                    parameters.setOverrideForType(overrides[choice - 1])
                } else if (isSubtitle) {
                    // Match Media3's None option, which still permits forced subtitles.
                    parameters.hideVideoSubtitles()
                }
                current.trackSelectionParameters = parameters.build()
                onTrackSelectionChanged?.invoke(trackType)
            }
        }
    }

    private fun audioCodecLabel(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_UHD_P2 -> "DTS:X"
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

    private fun layoutControls() {
        val row = findViewById<View>(R.id.video_controls_buttons) ?: return
        val more = findViewById<View>(R.id.video_more) ?: return
        val left = findViewById<ViewGroup>(R.id.video_track_buttons) ?: return
        val right = findViewById<ViewGroup>(R.id.video_extra_buttons) ?: return
        val transport = findViewById<ViewGroup>(R.id.video_transport) ?: return
        if (row.width == 0) return
        val available = row.width - row.paddingLeft - row.paddingRight
        val buttonWidth = more.layoutParams.width
        var transportWidth = 0
        for (index in 0 until transport.childCount) transportWidth += transport.getChildAt(index).layoutParams.width
        val portrait = width < height
        val tabletPortrait = portrait && resources.configuration.smallestScreenWidthDp >= WIDTH_DP_MEDIUM_LOWER_BOUND
        val compact = !tabletPortrait &&
            (portrait || available < transportWidth + 2 * buttonWidth * maxOf(left.childCount, right.childCount))
        val rewind = findViewById<View>(R.id.video_rewind)
        val forward = findViewById<View>(R.id.video_forward)
        val minTransportWidth = transportWidth - rewind.layoutParams.width - forward.layoutParams.width
        val fit = findViewById<View>(R.id.video_fit)
        val showFit = tabletPortrait || (!portrait && available >= minTransportWidth + buttonWidth * (1 + right.childCount))
        fit.visibility = if (showFit) VISIBLE else GONE
        val rightWidth = (0 until right.childCount).sumOf { index ->
            val button = right.getChildAt(index)
            if (button.visibility == GONE) 0 else button.layoutParams.width
        }
        val showSteps = !portrait && (!compact || available >= transportWidth + buttonWidth + rightWidth)
        left.visibility = if (compact) GONE else VISIBLE
        more.visibility = if (compact) VISIBLE else GONE
        rewind.visibility = if (showSteps) VISIBLE else GONE
        forward.visibility = rewind.visibility
        if (!showSteps) transportWidth = minTransportWidth

        // Keep transport centered where possible while clearing the controls on either side.
        val gap = (available - transportWidth) / 2f
        val leftWidth = if (compact) buttonWidth else (0 until left.childCount).sumOf { left.getChildAt(it).layoutParams.width }
        val minShift = leftWidth - gap
        transport.translationX = 0f.coerceIn(minShift, maxOf(minShift, gap - rightWidth))
    }

    internal fun showControlMenu(
        anchor: View,
        labels: List<String>,
        selected: Int,
        actionLabel: String? = null,
        onSelection: (Int) -> Unit,
    ) {
        if (!anchor.isAttachedToWindow) return
        controlMenu?.dismiss()

        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        if (frame.width() <= 2 * gap) return

        val itemLayout = if (selected in labels.indices) {
            android.R.layout.simple_list_item_single_choice
        } else {
            android.R.layout.simple_list_item_1
        }
        val adapter = ArrayAdapter(context, itemLayout, labels)
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackgroundFloating))
        val menuColor = colors.getColor(0, context.getColor(android.R.color.background_dark))
        colors.recycle()
        val shape = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(menuColor)
        }
        val list = ListView(context).apply {
            divider = null
            setPadding(0, gap, 0, gap)
            background = shape
            clipToOutline = true
        }
        if (actionLabel != null) {
            val action = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, list, false) as TextView
            action.text = actionLabel
            list.addFooterView(action, null, true)
        }
        list.apply {
            setAdapter(adapter)
            if (selected in labels.indices) {
                choiceMode = ListView.CHOICE_MODE_SINGLE
                setItemChecked(selected, true)
            }
        }

        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var contentWidth = (160 * density).toInt()
        val menuAdapter = list.adapter
        for (index in 0 until menuAdapter.count) {
            val item = menuAdapter.getView(index, null, list)
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
            setBackgroundDrawable(
                GradientDrawable().apply {
                    cornerRadius = shape.cornerRadius
                    setColor(menuColor)
                },
            )
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        captions?.addCaptioningChangeListener(captionListener)
        updateCaptionStyle()
    }

    override fun onDetachedFromWindow() {
        captions?.removeCaptioningChangeListener(captionListener)
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
                if (!canStartDrag) return false
                val current = player ?: return false
                val totalX = e2.x - downX
                val totalY = e2.y - downY
                if (scrollingNext) return true
                if (!seeking && playlistSwipeAllowed(playlistSwipeMinutes, current.duration) &&
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
            val insets = rootWindowInsets?.getInsets(WindowInsets.Type.systemGestures())
            val left = maxOf(edgeGuardPx, (insets?.left ?: 0).toFloat())
            val right = maxOf(edgeGuardPx, (insets?.right ?: 0).toFloat())
            canStartDrag = downX in left..(width - right)
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
                if (event.actionMasked == MotionEvent.ACTION_UP && seeking && lastSeekMs != C.TIME_UNSET) {
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

// Negative means Always. Unknown duration is eligible only in that mode.
internal fun playlistSwipeAllowed(minutes: Int, durationMs: Long): Boolean = minutes < 0 || minutes > 0 && durationMs in 1..(minutes * 60_000L)
