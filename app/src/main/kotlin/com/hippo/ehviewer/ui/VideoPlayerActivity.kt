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
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.ui.player.InternalVideoPlaylistRegistry
import com.hippo.ehviewer.ui.player.InternalVideoSource
import com.hippo.ehviewer.ui.player.PreparedInternalVideo
import com.hippo.ehviewer.ui.player.SeekPlayerView
import com.hippo.ehviewer.ui.player.StreamDocDataSource
import com.hippo.ehviewer.ui.player.progressGid
import com.hippo.ehviewer.util.setHdrColorMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    private var streamToken: String? = null
    private var playerView: SeekPlayerView? = null
    private var playlistSessionId: String? = null
    private var playlistIndex: Int = 0
    private var changingItem = false
    private var rotateWithVideo = true
    private var scrollToNext = true
    private var lastVideoSize: VideoSize = VideoSize.UNKNOWN
    private var pipControlRegistered = false

    /** Gallery id of the file currently loaded. Same row as reader progress. */
    private var progressGid = 0L

    /** Non-zero until duration is known and the saved position has been applied. */
    private var pendingResumeGid = 0L

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setHdrColorMode(on = true)
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
        savePlaybackProgress()
        try {
            playPrepared(
                uri = playUri,
                title = title,
                mimeType = mimeType,
                network = networkToken != null,
                holdForResume = nextSource != null &&
                    Settings.media3PlaybackResume.value != Settings.MEDIA3_RESUME_OFF,
            )
        } catch (e: Throwable) {
            logcat("VideoPlayer", e)
            token?.let(StreamDocumentRegistry::remove)
            return oldToken != null
        }
        streamToken = token
        playlistSessionId = nextSession
        playlistIndex = nextIndex
        armResume(nextSource)
        if (oldToken != null && oldToken != token) StreamDocumentRegistry.remove(oldToken)
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
        rotateWithVideo = Settings.videoRotateWithVideo.value
        scrollToNext = Settings.videoScrollToNext.value
        playerView?.scrollToNextEnabled = scrollToNext
        playerView?.onPlaylistScroll = { delta -> moveInPlaylist(delta) }
        findViewById<ImageButton>(R.id.video_rotate).setOnClickListener { toggleRotateWithVideo() }
        refreshRotateButton()
        val pip = findViewById<ImageButton>(R.id.video_pip)
        val pipAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        pip.visibility = if (pipAvailable) View.VISIBLE else View.GONE
        pip.setOnClickListener { enterPip() }
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
                logcat("VideoPlayer", e)
            } finally {
                changingItem = false
                updatePlaylistButtons()
            }
        }
    }

    private fun switchToPrepared(index: Int, source: InternalVideoSource, prepared: PreparedInternalVideo) {
        val oldToken = streamToken
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
                holdForResume = Settings.media3PlaybackResume.value != Settings.MEDIA3_RESUME_OFF,
            )
        } catch (e: Throwable) {
            StreamDocumentRegistry.remove(prepared.token)
            throw e
        }
        streamToken = prepared.token
        playlistIndex = index
        if (oldToken != prepared.token) oldToken?.let(StreamDocumentRegistry::remove)
        armResume(source)
    }

    private fun playPrepared(
        uri: Uri,
        title: String?,
        mimeType: String?,
        network: Boolean,
        holdForResume: Boolean,
    ) {
        // Drop the previous file's id before prepare callbacks, so a reset position
        // is not written over the progress just saved for that file.
        progressGid = 0L
        pendingResumeGid = 0L
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!title.isNullOrBlank()) {
                    setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                }
            }
            .build()

        player?.takeIf { playerUsesNetworkSource == network }?.let { existing ->
            existing.setMediaItem(mediaItem)
            existing.prepare()
            existing.playWhenReady = !holdForResume
            return
        }

        val previous = player
        player = null
        playerUsesNetworkSource = null
        playerView?.player = null
        runCatching { previous?.release() }

        val builder = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .setHandleAudioBecomingNoisy(true)

        if (network) {
            builder.setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(StreamDocDataSource.Factory()),
            )
        }

        val exo = builder.build()
        exo.setMediaItem(mediaItem)
        exo.addListener(
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    lastVideoSize = videoSize
                    applySystemOrientationForVideo(videoSize)
                    updatePipParams()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePipParams()
                    if (isPlaying) {
                        scheduleProgressSave()
                    } else {
                        playerView?.removeCallbacks(progressSaveRunnable)
                        savePlaybackProgress()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) tryResume()
                    if (playbackState == Player.STATE_ENDED) moveInPlaylist(1)
                    updatePipParams()
                }

                override fun onPlayerError(error: PlaybackException) {
                    logcat("VideoPlayer", error)
                }
            },
        )
        try {
            player = exo
            playerUsesNetworkSource = network
            playerView?.player = exo
            exo.prepare()
            exo.playWhenReady = !holdForResume
        } catch (e: Throwable) {
            if (player === exo) {
                player = null
                playerUsesNetworkSource = null
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
        val gid = source?.progressGid() ?: 0L
        progressGid = gid
        pendingResumeGid = if (
            gid != 0L && Settings.media3PlaybackResume.value != Settings.MEDIA3_RESUME_OFF
        ) {
            gid
        } else {
            0L
        }
        tryResume()
    }

    private fun tryResume() {
        val gid = pendingResumeGid
        if (gid == 0L) return
        val exo = player ?: return
        val duration = exo.duration
        if (duration <= 0L || duration == C.TIME_UNSET) {
            if (exo.playbackState == Player.STATE_READY) {
                pendingResumeGid = 0L
                exo.playWhenReady = true
            }
            return
        }
        val mode = Settings.media3PlaybackResume.value
        pendingResumeGid = 0L
        val autoSkip = mode == Settings.MEDIA3_RESUME_AUTO && duration <= RESUME_AUTO_MIN_MS
        if (mode == Settings.MEDIA3_RESUME_OFF || autoSkip) {
            exo.playWhenReady = true
            return
        }
        lifecycleScope.launch {
            val saved = runCatching { EhDB.getReadProgress(gid) }.getOrDefault(0).toLong()
            if (progressGid == gid && saved >= 1_000L && saved < duration - RESUME_END_MARGIN_MS) {
                player?.seekTo(saved)
            }
            if (progressGid == gid) player?.playWhenReady = true
        }
    }

    private fun scheduleProgressSave() {
        val view = playerView ?: return
        view.removeCallbacks(progressSaveRunnable)
        view.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL_MS)
    }

    /** Writes the playhead into the same progress row the reader uses for this file. */
    private fun savePlaybackProgress() {
        val gid = progressGid
        if (gid == 0L || pendingResumeGid == gid) return
        val exo = player ?: return
        val page = storedPositionMs(exo)
        val source = currentSource()
        lifecycleScope.launch {
            runCatching {
                if (source != null && EhDB.loadGalleryInfo(gid) == null) {
                    OpenFileExternally.recordVideoPlaybackHistory(source)
                }
                EhDB.putReadProgress(gid, page)
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
        playerView?.requestLayout()
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
        playerUsesNetworkSource = null
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
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYLIST_SESSION = "playlist_session"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"

        private const val CONTROLLER_TIMEOUT_MS = 2_800
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val RESUME_AUTO_MIN_MS = 3 * 60 * 1000L
        private const val RESUME_END_MARGIN_MS = 3_000L
        private const val ACTION_PIP_PLAY_PAUSE = "com.hippo.ehviewer.action.PIP_PLAY_PAUSE"

        fun intent(
            context: Context,
            uri: Uri,
            title: String,
            mimeType: String,
            streamToken: String,
            playlistSessionId: String? = null,
            playlistIndex: Int = 0,
        ): Intent = Intent(context, VideoPlayerActivity::class.java).apply {
            setDataAndType(uri, mimeType)
            putExtra(EXTRA_STREAM_TOKEN, streamToken)
            putExtra(EXTRA_TITLE, title)
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
