package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.ui.player.StreamDocDataSource
import com.hippo.ehviewer.util.setHdrColorMode

/**
 * Full-screen in-app video player using Media3 [PlayerView] built-in controls.
 *
 * - **Local:** content:// streamdoc → real seekable PFD (no FUSE).
 * - **SMB/WebDAV:** [StreamDocDataSource] → [com.hippo.ehviewer.library.VideoDirectLinkByteSource]
 *   RAM sliding window + dual-lane prefetch (no AppFuse proxy).
 *
 * Basic HDR: window COLOR_MODE_HDR when the display supports it; Media3/SurfaceView
 * presents HDR10 / HLG / etc. when the codec and panel allow it.
 */
@UnstableApi
class VideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var streamToken: String? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setHdrColorMode(on = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)

        val token = intent.getStringExtra(EXTRA_STREAM_TOKEN)
        streamToken = token
        val title = intent.getStringExtra(EXTRA_TITLE)
        val mimeType = intent.type

        val entry = token?.let { StreamDocumentRegistry.get(it) }
        // Network stream-doc: openSource present → native DataSource (not FUSE).
        // Local/SAF: openFileDescriptor present → content URI with real PFD.
        val networkToken = token?.takeIf { entry?.openSource != null }
        val playUri = when {
            networkToken != null -> StreamDocDataSource.uriFor(networkToken)
            intent.data != null -> intent.data!!
            token != null -> StreamDocumentProvider.uriFor(token)
            else -> {
                finish()
                return
            }
        }

        val view = findViewById<PlayerView>(R.id.player_view)
        playerView = view
        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        hideSystemBars()

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

        if (networkToken != null) {
            // Direct SMB/WebDAV reads + RAM window; never openProxyFileDescriptor.
            builder.setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(StreamDocDataSource.Factory(networkToken)),
            )
        }

        val exo = builder.build()
        player = exo
        view.player = exo

        val mediaItem = MediaItem.Builder()
            .setUri(playUri)
            .apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!title.isNullOrBlank()) {
                    setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .build(),
                    )
                }
            }
            .build()
        exo.setMediaItem(mediaItem)
        exo.playWhenReady = true
        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    logcat("VideoPlayer", error)
                }
            },
        )
        exo.prepare()
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
        playerView?.player = null
        playerView = null
        player?.release()
        player = null
        streamToken?.let { token ->
            StreamDocumentRegistry.remove(token)
            streamToken = null
        }
        super.onDestroy()
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

        fun intent(
            context: Context,
            uri: Uri,
            title: String,
            mimeType: String,
            streamToken: String,
        ): Intent = Intent(context, VideoPlayerActivity::class.java).apply {
            // Local path still uses content URI; network path is re-resolved from token.
            setDataAndType(uri, mimeType)
            putExtra(EXTRA_STREAM_TOKEN, streamToken)
            putExtra(EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
