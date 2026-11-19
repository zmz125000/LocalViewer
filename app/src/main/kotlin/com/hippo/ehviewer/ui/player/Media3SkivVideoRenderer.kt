package com.hippo.ehviewer.ui.player

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.R as EffectResources
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import com.hippo.ehviewer.R
import java.io.InputStream
import java.util.concurrent.Executor

@UnstableApi
internal class Media3SkivVideoRenderer(builder: Builder, private val piecewise: Boolean) : MediaCodecVideoRenderer(builder) {
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val selector = skivDecoderSelector(mediaCodecSelector, format)
        val support = super.supportsFormat(selector, format)
        if (selector !== mediaCodecSelector && RendererCapabilities.getFormatSupport(support) < C.FORMAT_EXCEEDS_CAPABILITIES) {
            // Let playback select the track and report a decoder error so it can retry in HDR.
            return super.supportsFormat(mediaCodecSelector, format)
        }
        return support
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> = super.getDecoderInfos(skivDecoderSelector(mediaCodecSelector, format), format, requiresSecureDecoder)

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        // Media3 otherwise puts the Dolby Vision profile number into the base decoder's config.
        val codecFormat = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && codecMimeType != MimeTypes.VIDEO_DOLBY_VISION) {
            format.buildUpon().setSampleMimeType(codecMimeType).setCodecs(null).build()
        } else {
            format
        }
        return super.getMediaFormat(
            codecFormat,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId,
        )
    }

    override fun maybeInitializeProcessingPipeline(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && !hasCompatibleHdrBase(format)) {
            throw createRendererException(
                UnsupportedOperationException("SKIV needs a compatible Dolby Vision HDR base layer"),
                format,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            )
        }
        return super.maybeInitializeProcessingPipeline(format)
    }

    // A native Dolby Vision decoder can process the RPU itself. Use the compatible base
    // decoder so the OpenGL sampler receives the PQ or HLG signal described by the track.
    private fun skivDecoderSelector(selector: MediaCodecSelector, format: Format): MediaCodecSelector {
        if (!hasCompatibleHdrBase(format)) return selector
        return MediaCodecSelector { mimeType, secure, tunneling ->
            if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) emptyList() else selector.getDecoderInfos(mimeType, secure, tunneling)
        }
    }

    private fun hasCompatibleHdrBase(format: Format): Boolean {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return false
        val color = format.colorInfo ?: return false
        return color.isDataSpaceValid && color.colorSpace == C.COLOR_SPACE_BT2020 &&
            ColorInfo.isTransferHdr(color) && MediaCodecUtil.getAlternativeCodecMimeType(format) != null
    }

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
