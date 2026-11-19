package com.hippo.ehviewer.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.R as EffectResources
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import com.hippo.ehviewer.R
import java.io.InputStream
import java.util.concurrent.Executor

@UnstableApi
internal class Media3SkivVideoRenderer(builder: Builder, private val piecewise: Boolean) : MediaCodecVideoRenderer(builder) {
    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper {
        val shaderContext = SkivShaderContext(context.applicationContext, piecewise)
        val factory = SingleInputVideoGraph.Factory()
        return PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .setClock(clock)
            .setVideoGraphFactory(object : VideoGraph.Factory by factory {
                override fun create(
                    context: Context,
                    outputColorInfo: ColorInfo,
                    debugViewProvider: DebugViewProvider,
                    listener: VideoGraph.Listener,
                    listenerExecutor: Executor,
                    initialTimestampOffsetUs: Long,
                    renderFramesAutomatically: Boolean,
                ): VideoGraph = factory.create(
                    shaderContext,
                    outputColorInfo,
                    debugViewProvider,
                    listener,
                    listenerExecutor,
                    initialTimestampOffsetUs,
                    renderFramesAutomatically,
                )
            })
            .build()
            .apply { setRequestOpenGlToneMapping(true) }
    }
}

/**
 * Media3 applies its HDR-to-SDR curve in the input sampler before the video effects run.
 * Replace just that function in the pinned Media3 shaders for this playback graph.
 */
@Suppress("DEPRECATION")
private class SkivShaderContext(context: Context, piecewise: Boolean) : ContextWrapper(context) {
    private val base = context.resources
    private val shader by lazy {
        base.openRawResource(R.raw.media3_skiv_tone_mapping).bufferedReader().use { it.readText() }
    }
    private val patchedResources = object : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun openRawResource(id: Int): InputStream {
            if (id != EffectResources.raw.fragment_shader_transformation_external_yuv_es3 &&
                id != EffectResources.raw.fragment_shader_transformation_hdr_internal_es3
            ) {
                return base.openRawResource(id)
            }

            val source = base.openRawResource(id).bufferedReader().use { it.readText() }
            val function = "highp vec3 applyBt2020ToBt709Ootf(highp vec3 linearRgbBt2020) {"
            val start = source.indexOf(function)
            val end = if (start >= 0) source.indexOf("\n}", start) else -1
            check(end >= 0) { "Media3 HDR sampler shader has changed" }
            val mode = if (piecewise) "#define SKIV_PIECEWISE 1" else "#define SKIV_PIECEWISE 0"
            return source.replaceRange(
                start,
                end + 2,
                "$mode\n$shader\n$function\n  return skivToBt709(linearRgbBt2020);\n}",
            ).byteInputStream()
        }
    }

    override fun getResources(): Resources = patchedResources
}
