package com.hippo.ehviewer.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.ehviewer.core.database.model.ProgressInfo
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withNonCancellableContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.ui.player.InternalVideoPlaylistRegistry
import com.hippo.ehviewer.ui.player.InternalVideoSource
import com.hippo.ehviewer.ui.player.Media3AudioBoostProcessor
import com.hippo.ehviewer.ui.player.Media3AudioRenderersFactory
import com.hippo.ehviewer.ui.player.Media3SubtitleParserFactory
import com.hippo.ehviewer.ui.player.PreparedInternalVideo
import com.hippo.ehviewer.ui.player.SeekPlayerView
import com.hippo.ehviewer.ui.player.StreamDocDataSource
import com.hippo.ehviewer.ui.player.progressGid
import com.hippo.ehviewer.ui.player.restoreVideoTrack
import com.hippo.ehviewer.ui.player.videoTrackKey
import com.hippo.ehviewer.util.setReaderColorMode
import com.hippo.ehviewer.util.supportsWideColorGamut
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Full-screen in-app Media3 player.
 *
 * Local: streamdoc content URI (seekable PFD). Network: [StreamDocDataSource] from the media URI.
 * Folder playlist next/prev / end-of-file reuses the same [ExoPlayer] when transport matches.
 */
@UnstableApi
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playerUsesNetworkSource: Boolean? = null
    private var playerHdrMode = Settings.MEDIA3_HDR
    private var wideColorOutputFailed = false
    private var playerAudioMode = Settings.MEDIA3_AUDIO_DEVICE
    private var defaultTrackSelection = TrackSelectionParameters.DEFAULT
    private var audioBoostDb = 0
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var audioBoostProcessor: Media3AudioBoostProcessor? = null
    private var streamToken: String? = null
    private var subtitleTokens: List<String> = emptyList()
    private var playerView: SeekPlayerView? = null
    private var playlistSessionId: String? = null
    private var playlistIndex: Int = 0
    private var changingItem = false
    private var rotateWithVideo = true
    private var lastVideoSize: VideoSize = VideoSize.UNKNOWN
    private var pipControlRegistered = false

    /** Gallery id of the file currently loaded. Same row as reader progress. */
    private var progressGid = 0L

    /** Non-zero until stored progress and track choices have been applied. */
    private var pendingResumeGid = 0L
    private var resumeProgress: ProgressInfo? = null
    private var readProgressJob: Job? = null
    private val trackChoicesDuringResume = mutableSetOf<Int>()

    private val progressSaveRunnable = Runnable {
        savePlaybackProgress()
        if (player?.isPlaying == true) scheduleProgressSave()
    }

    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_PLAY_PAUSE) return
            val exo = player ?: return
            when {
                exo.playbackState == Player.STATE_ENDED -> {
                    exo.seekTo(0L)
                    exo.play()
                }
                exo.isPlaying -> exo.pause()
                else -> exo.play()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        applyVideoColorMode(effectiveVideoColorMode(Settings.media3HdrToneMapping.value))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)

        val view = findViewById<SeekPlayerView>(R.id.player_view)
        playerView = view
        view.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        view.controllerAutoShow = false

        view.onClosePlayer = { finish() }
        bindControls()
        hideSystemBars()
        if (!applyPlayIntent(intent, replacePlaylist = false)) {
            finish()
            return
        }
        registerPipControl()
        view.hideController()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!applyPlayIntent(intent, replacePlaylist = true)) return
        playerView?.hideController()
    }

    /** @return false when the intent has no playable URI. */
    private fun applyPlayIntent(intent: Intent, replacePlaylist: Boolean): Boolean {
        val token = intent.getStringExtra(EXTRA_STREAM_TOKEN)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val mimeType = intent.type
        val entry = token?.let { StreamDocumentRegistry.get(it) }
        val networkToken = token?.takeIf { entry?.openSource != null }
        val playUri = when {
            networkToken != null -> StreamDocDataSource.uriFor(networkToken)
            intent.data != null -> intent.data!!
            token != null -> StreamDocumentProvider.uriFor(token)
            else -> return false
        }
        val oldToken = streamToken
        val oldPlaylist = playlistSessionId
        val nextSession = intent.getStringExtra(EXTRA_PLAYLIST_SESSION)
        val nextIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
        val nextSource = InternalVideoPlaylistRegistry.get(nextSession)?.items?.getOrNull(nextIndex)
        val nextSubtitleTokens = intent.getStringArrayExtra(EXTRA_SUBTITLE_TOKENS)?.toList().orEmpty()
        val oldSubtitleTokens = subtitleTokens
        savePlaybackProgress()
        try {
            playPrepared(
                uri = playUri,
                title = title,
                mimeType = mimeType,
                network = networkToken != null,
                subtitleTokens = nextSubtitleTokens,
                holdForResume = nextSource != null,
            )
        } catch (e: Throwable) {
            Log.e("VideoPlayer", "Unable to open the media item", e)
            nextSubtitleTokens.filterNot(oldSubtitleTokens::contains).forEach(StreamDocumentRegistry::remove)
            token?.let(StreamDocumentRegistry::remove)
            return oldToken != null
        }
        streamToken = token
        subtitleTokens = nextSubtitleTokens
        playlistSessionId = nextSession
        playlistIndex = nextIndex
        armResume(nextSource)
        if (oldToken != null && oldToken != token) StreamDocumentRegistry.remove(oldToken)
        oldSubtitleTokens.filterNot(nextSubtitleTokens::contains).forEach(StreamDocumentRegistry::remove)
        if (replacePlaylist && oldPlaylist != null && oldPlaylist != playlistSessionId) {
            InternalVideoPlaylistRegistry.remove(oldPlaylist)
        }
        updatePlaylistButtons()
        return true
    }

    override fun onStart() {
        super.onStart()
        // Resume seeks once duration is known. Starting here would play from 0 first.
        if (pendingResumeGid == 0L) player?.playWhenReady = true
    }

    override fun onStop() {
        playerView?.removeCallbacks(progressSaveRunnable)
        savePlaybackProgress()
        // PiP stays started. Pausing here would freeze the small window.
        if (!isInPictureInPictureMode) player?.playWhenReady = false
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Closing the system window stops the activity (CREATED). Expanding it resumes first.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            finish()
            return
        }
        playerView?.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerView?.hideController()
        } else {
            hideSystemBars()
            applySystemOrientationForVideo(lastVideoSize)
        }
        updatePipParams()
    }

    override fun onDestroy() {
        unregisterPipControl()
        releasePlayer()
        streamToken?.let(StreamDocumentRegistry::remove)
        streamToken = null
        subtitleTokens.forEach(StreamDocumentRegistry::remove)
        subtitleTokens = emptyList()
        InternalVideoPlaylistRegistry.remove(playlistSessionId)
        playlistSessionId = null
        playerView = null
        super.onDestroy()
    }

    private fun bindControls() {
        findViewById<ImageButton>(R.id.video_previous).setOnClickListener { moveInPlaylist(-1) }
        findViewById<ImageButton>(R.id.video_next).setOnClickListener { moveInPlaylist(1) }
        findViewById<ImageButton>(R.id.video_rewind).setOnClickListener { seekByStep(forward = false) }
        findViewById<ImageButton>(R.id.video_forward).setOnClickListener { seekByStep(forward = true) }
        findViewById<ImageButton>(R.id.video_audio_boost).apply {
            tooltipText = contentDescription
            setOnClickListener(::showAudioBoostMenu)
        }
        findViewById<ImageButton>(R.id.video_hdr).apply {
            tooltipText = contentDescription
            setOnClickListener(::showHdrMenu)
        }
        findViewById<ImageButton>(R.id.video_audio_output).apply {
            tooltipText = contentDescription
            setOnClickListener(::showAudioOutputMenu)
        }
        findViewById<ImageButton>(R.id.video_volume).apply {
            tooltipText = contentDescription
            setOnClickListener(::showVolumeMenu)
        }
        findViewById<ImageButton>(R.id.video_brightness).apply {
            tooltipText = contentDescription
            setOnClickListener(::showBrightnessMenu)
        }
        updateVolumeButton()
        updateBrightnessButton()
        findViewById<ImageButton>(R.id.video_more).setOnClickListener(::showMoreControls)
        playerAudioMode = Settings.media3AudioDownmixing.value
        updatePlaybackOptionButtons()
        rotateWithVideo = Settings.videoRotateWithVideo.value
        playerView?.playlistSwipeMinutes = Settings.videoScrollToNext.value
        playerView?.onPlaylistScroll = { delta -> moveInPlaylist(delta) }
        playerView?.onTrackSelectionChanged = { type ->
            if (pendingResumeGid != 0L) trackChoicesDuringResume += type
            savePlaybackProgress()
        }
        findViewById<ImageButton>(R.id.video_rotate).setOnClickListener { toggleRotateWithVideo() }
        refreshRotateButton()
        findViewById<ImageButton>(R.id.video_fit).setOnClickListener { toggleVideoFit() }
        findViewById<ImageButton>(R.id.video_portrait_fit).setOnClickListener { toggleVideoFit() }
        refreshVideoFitButton()
        val pip = findViewById<ImageButton>(R.id.video_pip)
        val pipAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        pip.visibility = if (pipAvailable) View.VISIBLE else View.GONE
        pip.setOnClickListener { enterPip() }
    }

    private fun audioBoostLabel(gainDb: Int): String = if (gainDb == 0) {
        getString(R.string.video_audio_boost_off)
    } else {
        getString(R.string.video_audio_boost_gain, gainDb)
    }

    private fun showAudioBoostMenu(anchor: View) {
        val levels = (0..16 step 2).toList()
        playerView?.showControlMenu(anchor, levels.map(::audioBoostLabel), levels.indexOf(audioBoostDb)) { index ->
            val level = levels[index]
            if (level == audioBoostDb) return@showControlMenu
            val previous = audioBoostDb
            audioBoostDb = level
            if (level == 0 || previous == 0) {
                if (!restartCurrentMediaItem(playerHdrMode)) audioBoostDb = previous
            } else {
                audioBoostProcessor?.setGainDb(level)
            }
            updatePlaybackOptionButtons()
        }
    }

    private fun showAudioOutputMenu(anchor: View) {
        val labels = resources.getStringArray(R.array.media3_audio_downmixing_entries)
        val modes = resources.getIntArray(R.array.media3_audio_downmixing_values)
        playerView?.showControlMenu(anchor, labels.asList(), modes.indexOf(playerAudioMode)) { index ->
            val mode = modes[index]
            if (mode == playerAudioMode || restartCurrentMediaItem(playerHdrMode, mode)) {
                Settings.media3AudioDownmixing.value = mode
            }
        }
    }

    private fun showVolumeMenu(anchor: View) {
        val stream = AudioManager.STREAM_MUSIC
        val volume = audioManager.getStreamVolume(stream)
        updateVolumeButton(volume)
        playerView?.showLevelMenu(
            anchor,
            getString(R.string.video_volume),
            audioManager.getStreamMinVolume(stream)..audioManager.getStreamMaxVolume(stream),
            volume,
            adjustable = !audioManager.isVolumeFixed,
        ) { level ->
            if (level != null && level != audioManager.getStreamVolume(stream)) {
                audioManager.setStreamVolume(stream, level, 0)
            }
            val current = audioManager.getStreamVolume(stream)
            updateVolumeButton(current)
            current
        }
    }

    private fun brightnessLevel(): Int? = window.attributes.screenBrightness.takeIf { it >= 0f }
        ?.let { (it * 100).roundToInt() }

    private fun showBrightnessMenu(anchor: View) {
        val brightness = AndroidSettings.System.getInt(contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, 128)
        playerView?.showLevelMenu(
            anchor,
            getString(R.string.video_brightness),
            1..100,
            brightnessLevel(),
            systemLevel = (brightness * 100f / 255f).roundToInt(),
        ) { level ->
            if (level != brightnessLevel()) {
                window.attributes = window.attributes.apply {
                    screenBrightness = level?.div(100f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                updateBrightnessButton()
            }
            level
        }
    }

    private fun updateVolumeButton(volume: Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        findViewById<ImageButton>(R.id.video_volume).apply {
            setImageResource(if (volume == 0) R.drawable.ic_video_volume_muted else R.drawable.ic_video_volume)
            stateDescription = if (volume == 0) {
                getString(R.string.video_volume_mute)
            } else {
                getString(R.string.video_level_percent, (volume * 100.0 / maxOf(maxVolume, 1)).roundToInt())
            }
        }
    }

    private fun updateBrightnessButton() {
        findViewById<ImageButton>(R.id.video_brightness).stateDescription =
            brightnessLevel()?.let { getString(R.string.video_level_percent, it) } ?: getString(R.string.video_level_system)
    }

    private fun showMoreControls(anchor: View) {
        val view = playerView ?: return
        val buttons = listOf(
            androidx.media3.ui.R.id.exo_audio_track,
            R.id.video_subtitle,
            R.id.video_audio_boost,
            R.id.video_audio_output,
            R.id.video_hdr,
        ).map { findViewById<ImageButton>(it) }.filter { it.isEnabled && it.visibility == View.VISIBLE }.toMutableList()
        val fit = findViewById<ImageButton>(R.id.video_fit)
        if (fit.visibility == View.GONE && findViewById<View>(R.id.video_portrait_fit).visibility == View.GONE) {
            buttons += fit
        }
        val labels = buttons.map { it.contentDescription.toString() }
        view.showControlMenu(anchor, labels, -1) { index ->
            val button = buttons[index]
            when (button.id) {
                androidx.media3.ui.R.id.exo_audio_track -> view.showTrackMenu(anchor, C.TRACK_TYPE_AUDIO)
                R.id.video_subtitle -> view.showTrackMenu(anchor, C.TRACK_TYPE_TEXT)
                R.id.video_audio_boost -> showAudioBoostMenu(anchor)
                R.id.video_audio_output -> showAudioOutputMenu(anchor)
                R.id.video_hdr -> showHdrMenu(anchor)
                R.id.video_fit -> toggleVideoFit()
            }
        }
    }

    private fun showHdrMenu(anchor: View) {
        val labels = resources.getStringArray(R.array.media3_hdr_tone_mapping_entries)
        val modes = resources.getIntArray(R.array.media3_hdr_tone_mapping_values)
        playerView?.showControlMenu(anchor, labels.asList(), modes.indexOf(playerHdrMode)) { index ->
            val requestedMode = modes[index]
            val mode = effectiveVideoColorMode(requestedMode)
            if (mode == playerHdrMode || restartCurrentMediaItem(mode)) {
                Settings.media3HdrToneMapping.value = requestedMode
            }
        }
    }

    private fun updatePlaybackOptionButtons() {
        findViewById<ImageButton>(R.id.video_audio_boost).apply {
            isSelected = audioBoostDb > 0
            alpha = if (isSelected) 1f else 0.6f
            stateDescription = audioBoostLabel(audioBoostDb)
        }
        findViewById<ImageButton>(R.id.video_audio_output).apply {
            val modes = resources.getIntArray(R.array.media3_audio_downmixing_values)
            stateDescription = resources.getStringArray(R.array.media3_audio_downmixing_entries).getOrNull(modes.indexOf(playerAudioMode))
        }
        findViewById<ImageButton>(R.id.video_hdr).apply {
            isSelected = playerHdrMode == Settings.MEDIA3_HDR
            alpha = if (isSelected) 1f else 0.6f
            val modes = resources.getIntArray(R.array.media3_hdr_tone_mapping_values)
            stateDescription = resources.getStringArray(R.array.media3_hdr_tone_mapping_entries)[modes.indexOf(playerHdrMode)]
        }
    }

    private fun toggleRotateWithVideo() {
        rotateWithVideo = !rotateWithVideo
        Settings.videoRotateWithVideo.value = rotateWithVideo
        refreshRotateButton()
        if (rotateWithVideo) {
            applySystemOrientationForVideo(lastVideoSize)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun refreshRotateButton() {
        findViewById<ImageButton>(R.id.video_rotate).apply {
            isSelected = rotateWithVideo
            alpha = if (rotateWithVideo) 1f else 0.4f
        }
    }

    private fun toggleVideoFit() {
        val view = playerView ?: return
        view.resizeMode = if (view.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        refreshVideoFitButton()
    }

    private fun refreshVideoFitButton() {
        val cropped = playerView?.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        val icon = if (cropped) {
            androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_exit
        } else {
            androidx.media3.ui.R.drawable.exo_styled_controls_fullscreen_enter
        }
        val description = getString(if (cropped) R.string.video_fit_to_screen else R.string.video_crop_to_fill)
        for (id in listOf(R.id.video_fit, R.id.video_portrait_fit)) {
            findViewById<ImageButton>(id).apply {
                setImageResource(icon)
                contentDescription = description
                tooltipText = description
            }
        }
    }

    private fun seekByStep(forward: Boolean) {
        val step = skipStepMs(player?.duration ?: C.TIME_UNSET)
        seekBy(if (forward) step else -step)
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        val target = if (duration > 0L && duration != C.TIME_UNSET) {
            (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        }
        exo.seekTo(target)
    }

    private fun moveInPlaylist(delta: Int) {
        if (changingItem) return
        val session = InternalVideoPlaylistRegistry.get(playlistSessionId) ?: return
        val target = playlistIndex + delta
        val source = session.items.getOrNull(target) ?: return
        changingItem = true
        updatePlaylistButtons()
        savePlaybackProgress()
        lifecycleScope.launch {
            try {
                switchToPrepared(target, source, OpenFileExternally.prepareInternalVideo(source))
                // Next/prev (and end-of-file auto-next) skips browse open — record here.
                // Parent browse-dir pin is bumped inside putHistoryInfo.
                OpenFileExternally.recordVideoPlaybackHistory(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("VideoPlayer", "Unable to open the media item", e)
            } finally {
                changingItem = false
                updatePlaylistButtons()
            }
        }
    }

    private fun switchToPrepared(index: Int, source: InternalVideoSource, prepared: PreparedInternalVideo) {
        val oldToken = streamToken
        val oldSubtitleTokens = subtitleTokens
        val playUri = if (prepared.network) {
            StreamDocDataSource.uriFor(prepared.token)
        } else {
            prepared.uri
        }
        try {
            playPrepared(
                uri = playUri,
                title = prepared.displayName,
                mimeType = prepared.mimeType,
                network = prepared.network,
                subtitleTokens = prepared.subtitleTokens,
                holdForResume = true,
            )
        } catch (e: Throwable) {
            prepared.subtitleTokens.forEach(StreamDocumentRegistry::remove)
            StreamDocumentRegistry.remove(prepared.token)
            throw e
        }
        streamToken = prepared.token
        subtitleTokens = prepared.subtitleTokens
        playlistIndex = index
        if (oldToken != prepared.token) oldToken?.let(StreamDocumentRegistry::remove)
        oldSubtitleTokens.filterNot(prepared.subtitleTokens::contains).forEach(StreamDocumentRegistry::remove)
        armResume(source)
    }

    private fun playPrepared(
        uri: Uri,
        title: String?,
        mimeType: String?,
        network: Boolean,
        subtitleTokens: List<String>,
        holdForResume: Boolean,
    ) {
        // Drop the previous file's id before prepare callbacks, so a reset position
        // is not written over the progress just saved for that file.
        progressGid = 0L
        pendingResumeGid = 0L
        readProgressJob?.cancel()
        resumeProgress = null
        trackChoicesDuringResume.clear()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                var selectFirstSubtitle = true
                val subtitles = subtitleTokens.mapNotNull { token ->
                    val entry = StreamDocumentRegistry.get(token) ?: return@mapNotNull null
                    if (entry.mimeType !in MEDIA3_SUBTITLE_MIMES) return@mapNotNull null
                    val subtitle = MediaItem.SubtitleConfiguration.Builder(
                        StreamDocumentProvider.uriFor(token, entry.displayName),
                    )
                        .setMimeType(entry.mimeType)
                        .setId(entry.displayName)
                        .setLabel(entry.displayName)
                    if (selectFirstSubtitle) {
                        subtitle.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        selectFirstSubtitle = false
                    }
                    subtitle.build()
                }
                if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles)
                if (!title.isNullOrBlank()) {
                    setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                }
            }
            .build()

        val hdrMode = effectiveVideoColorMode(Settings.media3HdrToneMapping.value)
        player?.takeIf { playerUsesNetworkSource == network && playerHdrMode == hdrMode }?.let { existing ->
            existing.playWhenReady = false
            existing.trackSelectionParameters = defaultTrackSelection
            existing.setMediaItem(mediaItem)
            existing.prepare()
            existing.playWhenReady = !holdForResume
            return
        }

        startMediaItem(mediaItem, network, !holdForResume, hdrMode)
    }

    private fun restartCurrentMediaItem(hdrMode: Int, audioOutputMode: Int = playerAudioMode): Boolean {
        val current = player ?: return false
        val mediaItem = current.currentMediaItem ?: return false
        return runCatching {
            startMediaItem(
                mediaItem,
                playerUsesNetworkSource == true,
                current.playWhenReady,
                hdrMode,
                current.currentPosition,
                preservePlaybackSettings = true,
                audioOutputMode = audioOutputMode,
            )
        }.onFailure { Log.e("VideoPlayer", "Unable to restart playback", it) }.isSuccess
    }

    private fun effectiveVideoColorMode(mode: Int): Int {
        if (mode !in Settings.MEDIA3_HDR..Settings.MEDIA3_SKIV_PIECEWISE_WCG) return Settings.MEDIA3_HDR
        val sdrMode = Settings.media3SdrFallback(mode)
        return if (mode != sdrMode && (wideColorOutputFailed || !supportsWideColorGamut())) sdrMode else mode
    }

    private fun applyVideoColorMode(mode: Int, force: Boolean = false) {
        setReaderColorMode(
            hdr = mode == Settings.MEDIA3_HDR,
            wideColor = mode != Settings.media3SdrFallback(mode),
            force = force,
        )
    }

    private fun startMediaItem(
        mediaItem: MediaItem,
        network: Boolean,
        playWhenReady: Boolean,
        hdrMode: Int,
        positionMs: Long = C.TIME_UNSET,
        preservePlaybackSettings: Boolean = false,
        audioOutputMode: Int = playerAudioMode,
    ) {
        val previous = player
        // Audio boost needs decoded PCM to control the audio samples.
        val audioMode = if (audioOutputMode == Settings.MEDIA3_AUDIO_PASSTHROUGH && audioBoostDb > 0) {
            Settings.MEDIA3_AUDIO_DEVICE
        } else {
            audioOutputMode
        }
        val boost = if (audioBoostDb > 0) Media3AudioBoostProcessor(audioBoostDb) else null
        val renderersFactory = Media3AudioRenderersFactory(this, audioMode, hdrMode, boost)
        val builder = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setDeviceVolumeControlEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setSubtitleParserFactory(Media3SubtitleParserFactory())
        if (network) {
            mediaSourceFactory.setDataSourceFactory(DefaultDataSource.Factory(this, StreamDocDataSource.Factory()))
        }
        val exo = builder.setMediaSourceFactory(mediaSourceFactory).build()
        val defaultTracks = exo.trackSelectionParameters
        exo.addListener(
            object : Player.Listener {
                override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                    if (player !== exo) return
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    updateVolumeButton(current)
                    playerView?.refreshLevelMenu(R.id.video_volume, current)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (player !== exo) return
                    lastVideoSize = videoSize
                    applySystemOrientationForVideo(videoSize)
                    updatePipParams()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== exo) return
                    updatePipParams()
                    if (isPlaying) {
                        scheduleProgressSave()
                    } else {
                        playerView?.removeCallbacks(progressSaveRunnable)
                        savePlaybackProgress()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== exo) return
                    if (playbackState == Player.STATE_READY) tryResume()
                    if (playbackState == Player.STATE_ENDED) moveInPlaylist(1)
                    updatePipParams()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("VideoPlayer", "Media3 playback failed", error)
                    if (hdrMode != Settings.MEDIA3_HDR &&
                        (
                            error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                            )
                    ) {
                        playerView?.post {
                            if (player !== exo) return@post
                            val sdrMode = Settings.media3SdrFallback(hdrMode)
                            if (sdrMode != hdrMode && error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                                wideColorOutputFailed = true
                                if (restartCurrentMediaItem(sdrMode)) return@post
                            }
                            // Some devices cannot expose their HDR decoder output to OpenGL.
                            restartCurrentMediaItem(Settings.MEDIA3_HDR)
                        }
                    }
                }
            },
        )
        try {
            if (preservePlaybackSettings && previous != null) {
                exo.trackSelectionParameters = previous.trackSelectionParameters
                exo.playbackParameters = previous.playbackParameters
            }
            exo.setMediaItem(mediaItem, positionMs)
            if (hdrMode != Settings.MEDIA3_HDR) {
                // An empty effect list still enables the shader graph.
                exo.setVideoEffects(emptyList())
            }
            player = null
            playerView?.player = null
            runCatching { previous?.release() }
            player = exo
            playerUsesNetworkSource = network
            playerHdrMode = hdrMode
            playerAudioMode = audioOutputMode
            defaultTrackSelection = defaultTracks
            audioBoostProcessor = boost
            applyVideoColorMode(hdrMode)
            updatePlaybackOptionButtons()
            playerView?.player = exo
            exo.prepare()
            exo.playWhenReady = playWhenReady
        } catch (e: Throwable) {
            if (player === exo) {
                player = null
                playerUsesNetworkSource = null
                audioBoostProcessor = null
            }
            if (playerView?.player === exo) playerView?.player = null
            exo.release()
            throw e
        }
    }

    private fun applySystemOrientationForVideo(videoSize: VideoSize) {
        if (!rotateWithVideo || isInPictureInPictureMode) return
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        // Media3 applies rotation internally; width/height are already display size.
        val target = when {
            videoSize.width > videoSize.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            videoSize.height > videoSize.width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != target) requestedOrientation = target
    }

    private fun currentSource(): InternalVideoSource? = InternalVideoPlaylistRegistry.get(playlistSessionId)?.items?.getOrNull(playlistIndex)

    private fun armResume(source: InternalVideoSource?) {
        val gid = source?.progressGid() ?: return
        progressGid = gid
        pendingResumeGid = gid
        readProgressJob = lifecycleScope.launch {
            val saved = progressIo.withLock {
                try {
                    EhDB.getVideoProgress(gid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("VideoPlayer", "Unable to read video progress", e)
                    null
                }
            }
            resumeProgress = saved ?: ProgressInfo(gid, 0)
            tryResume()
        }
    }

    private fun tryResume() {
        val saved = resumeProgress ?: return
        val exo = player ?: return
        if (exo.playbackState != Player.STATE_READY || pendingResumeGid != saved.gid) return

        resumeProgress = null
        val parameters = exo.trackSelectionParameters.buildUpon()
        if (C.TRACK_TYPE_AUDIO !in trackChoicesDuringResume) parameters.restoreVideoTrack(exo, C.TRACK_TYPE_AUDIO, saved.audioTrack)
        if (C.TRACK_TYPE_TEXT !in trackChoicesDuringResume) parameters.restoreVideoTrack(exo, C.TRACK_TYPE_TEXT, saved.subtitleTrack)
        exo.trackSelectionParameters = parameters.build()

        val duration = exo.duration
        val mode = Settings.media3PlaybackResume.value
        if (duration != C.TIME_UNSET && mode != Settings.MEDIA3_RESUME_OFF &&
            (mode != Settings.MEDIA3_RESUME_AUTO || duration > RESUME_AUTO_MIN_MS) &&
            saved.page.toLong() in 1_000L until (duration - RESUME_END_MARGIN_MS)
        ) {
            exo.seekTo(saved.page.toLong())
        }
        pendingResumeGid = 0L
        if (trackChoicesDuringResume.isNotEmpty()) savePlaybackProgress()
        trackChoicesDuringResume.clear()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isInPictureInPictureMode) {
            exo.playWhenReady = true
        }
    }

    private fun scheduleProgressSave() {
        val view = playerView ?: return
        view.removeCallbacks(progressSaveRunnable)
        view.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL_MS)
    }

    private fun savePlaybackProgress() {
        val gid = progressGid
        if (gid == 0L || pendingResumeGid == gid) return
        val exo = player ?: return
        if (exo.currentTracks.isEmpty) return
        val progress = ProgressInfo(
            gid,
            storedPositionMs(exo),
            videoTrackKey(exo, C.TRACK_TYPE_AUDIO),
            videoTrackKey(exo, C.TRACK_TYPE_TEXT),
        )
        val source = currentSource()
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withNonCancellableContext {
                progressIo.withLock {
                    runCatching {
                        if (source != null && EhDB.loadGalleryInfo(gid) == null) {
                            OpenFileExternally.recordVideoPlaybackHistory(source)
                        }
                        EhDB.putVideoProgress(progress)
                    }.onFailure { Log.w("VideoPlayer", "Unable to save video progress", it) }
                }
            }
        }
    }

    private fun storedPositionMs(exo: Player): Int {
        val position = exo.currentPosition
        if (position < 1_000L) return 0
        val duration = exo.duration
        if (duration > 0L && duration != C.TIME_UNSET && position >= duration - RESUME_END_MARGIN_MS) {
            return 0
        }
        return position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun enterPip() {
        if (isInPictureInPictureMode) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val params = pipParams(autoEnter = false) ?: return
        enterPictureInPictureMode(params)
    }

    /** Home leaves a playing video in the system PiP window. Paused playback does not. */
    private fun updatePipParams() {
        val params = pipParams(autoEnter = player?.isPlaying == true) ?: return
        runCatching { setPictureInPictureParams(params) }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams? {
        val fraction = pipAspectFraction(
            lastVideoSize.width,
            lastVideoSize.height,
            lastVideoSize.pixelWidthHeightRatio,
        )
        val builder = PictureInPictureParams.Builder()
            .setAutoEnterEnabled(autoEnter)
            .setActions(listOfNotNull(pipPlayPauseAction()))
        if (fraction != null) {
            builder.setAspectRatio(Rational(fraction.first, fraction.second))
        }
        val view = playerView
        if (view != null) {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect) && !rect.isEmpty) builder.setSourceRectHint(rect)
        }
        return runCatching { builder.build() }.getOrNull()
    }

    private fun registerPipControl() {
        if (pipControlRegistered) return
        ContextCompat.registerReceiver(
            this,
            pipControlReceiver,
            IntentFilter(ACTION_PIP_PLAY_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        pipControlRegistered = true
    }

    private fun unregisterPipControl() {
        if (!pipControlRegistered) return
        unregisterReceiver(pipControlReceiver)
        pipControlRegistered = false
    }

    /** Play/pause on the system PiP window. The icon follows [player]. */
    private fun pipPlayPauseAction(): RemoteAction? {
        val exo = player ?: return null
        val playing = exo.isPlaying
        val title = getString(
            if (playing) {
                androidx.media3.ui.R.string.exo_controls_pause_description
            } else {
                androidx.media3.ui.R.string.exo_controls_play_description
            },
        )
        val icon = Icon.createWithResource(
            this,
            if (playing) {
                androidx.media3.ui.R.drawable.exo_icon_pause
            } else {
                androidx.media3.ui.R.drawable.exo_icon_play
            },
        )
        val pending = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(icon, title, title, pending)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        applyVideoColorMode(playerHdrMode, force = true)
        playerView?.requestLayout()
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
        playerUsesNetworkSource = null
        audioBoostProcessor = null
    }

    private fun updatePlaylistButtons() {
        val session = InternalVideoPlaylistRegistry.get(playlistSessionId)
        val previousEnabled = !changingItem && session?.items?.getOrNull(playlistIndex - 1) != null
        val nextEnabled = !changingItem && session?.items?.getOrNull(playlistIndex + 1) != null
        findViewById<ImageButton>(R.id.video_previous).setEnabledAppearance(previousEnabled)
        findViewById<ImageButton>(R.id.video_next).setEnabledAppearance(nextEnabled)
    }

    private fun ImageButton.setEnabledAppearance(value: Boolean) {
        isEnabled = value
        alpha = if (value) 1f else 0.35f
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_STREAM_TOKEN = "stream_token"
        const val EXTRA_SUBTITLE_TOKENS = "subtitle_tokens"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYLIST_SESSION = "playlist_session"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"

        private val progressIo = Mutex()

        private const val CONTROLLER_TIMEOUT_MS = 2_800
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val RESUME_AUTO_MIN_MS = 3 * 60 * 1000L
        private const val RESUME_END_MARGIN_MS = 3_000L
        private const val ACTION_PIP_PLAY_PAUSE = "com.hippo.ehviewer.action.PIP_PLAY_PAUSE"
        private val MEDIA3_SUBTITLE_MIMES = setOf(
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.TEXT_SSA,
            MimeTypes.TEXT_VTT,
            MimeTypes.APPLICATION_TTML,
        )

        fun intent(
            context: Context,
            uri: Uri,
            title: String,
            mimeType: String,
            streamToken: String,
            playlistSessionId: String? = null,
            playlistIndex: Int = 0,
            subtitleTokens: List<String> = emptyList(),
        ): Intent = Intent(context, VideoPlayerActivity::class.java).apply {
            setDataAndType(uri, mimeType)
            putExtra(EXTRA_STREAM_TOKEN, streamToken)
            putExtra(EXTRA_TITLE, title)
            if (subtitleTokens.isNotEmpty()) {
                putExtra(EXTRA_SUBTITLE_TOKENS, subtitleTokens.toTypedArray())
            }
            if (playlistSessionId != null) {
                putExtra(EXTRA_PLAYLIST_SESSION, playlistSessionId)
                putExtra(EXTRA_PLAYLIST_INDEX, playlistIndex)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * Android rejects a PiP aspect outside about 1:2.39 … 2.39:1.
 * Returns numerator to 10_000, or null when the video size is unknown.
 */
internal fun pipAspectFraction(width: Int, height: Int, pixelWidthHeightRatio: Float): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val raw = width * pixelWidthHeightRatio / height.toFloat()
    if (!raw.isFinite() || raw <= 0f) return null
    val clamped = raw.coerceIn(PIP_ASPECT_MIN, PIP_ASPECT_MAX)
    return (clamped * 10_000f).toInt().coerceAtLeast(1) to 10_000
}

/** Inside the platform limit so [android.util.Rational] is accepted. */
internal const val PIP_ASPECT_MIN = 0.42f

/** Inside the platform limit so [android.util.Rational] is accepted. */
internal const val PIP_ASPECT_MAX = 2.38f

/** Rewind / forward step from the video length. */
internal fun skipStepMs(durationMs: Long): Long = when {
    durationMs in 1L until 60_000L -> 3_000L
    durationMs in 60_000L..10 * 60_000L -> 5_000L
    else -> 10_000L
}
