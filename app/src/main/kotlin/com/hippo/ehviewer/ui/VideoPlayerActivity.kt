package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.hippo.ehviewer.R
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.ui.player.InternalVideoPlaylistRegistry
import com.hippo.ehviewer.ui.player.PreparedInternalVideo
import com.hippo.ehviewer.ui.player.SeekPlayerView
import com.hippo.ehviewer.ui.player.StreamDocDataSource
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

        bindControls()
        hideSystemBars()
        if (!applyPlayIntent(intent, replacePlaylist = false)) {
            finish()
            return
        }
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
        try {
            playPrepared(
                uri = playUri,
                title = title,
                mimeType = mimeType,
                network = networkToken != null,
            )
        } catch (e: Throwable) {
            logcat("VideoPlayer", e)
            token?.let(StreamDocumentRegistry::remove)
            return oldToken != null
        }
        streamToken = token
        playlistSessionId = intent.getStringExtra(EXTRA_PLAYLIST_SESSION)
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
        if (oldToken != null && oldToken != token) StreamDocumentRegistry.remove(oldToken)
        if (replacePlaylist && oldPlaylist != null && oldPlaylist != playlistSessionId) {
            InternalVideoPlaylistRegistry.remove(oldPlaylist)
        }
        updatePlaylistButtons()
        return true
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
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
    }

    private fun moveInPlaylist(delta: Int) {
        if (changingItem) return
        val session = InternalVideoPlaylistRegistry.get(playlistSessionId) ?: return
        val target = playlistIndex + delta
        val source = session.items.getOrNull(target) ?: return
        changingItem = true
        updatePlaylistButtons()
        lifecycleScope.launch {
            try {
                switchToPrepared(target, OpenFileExternally.prepareInternalVideo(source))
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

    private fun switchToPrepared(index: Int, prepared: PreparedInternalVideo) {
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
            )
        } catch (e: Throwable) {
            StreamDocumentRegistry.remove(prepared.token)
            throw e
        }
        streamToken = prepared.token
        playlistIndex = index
        if (oldToken != prepared.token) oldToken?.let(StreamDocumentRegistry::remove)
    }

    private fun playPrepared(
        uri: Uri,
        title: String?,
        mimeType: String?,
        network: Boolean,
    ) {
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
            existing.playWhenReady = true
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
                    applySystemOrientationForVideo(videoSize)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) moveInPlaylist(1)
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
            exo.playWhenReady = true
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
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        // Media3 applies rotation internally; width/height are already display size.
        val target = when {
            videoSize.width > videoSize.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            videoSize.height > videoSize.width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != target) requestedOrientation = target
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
