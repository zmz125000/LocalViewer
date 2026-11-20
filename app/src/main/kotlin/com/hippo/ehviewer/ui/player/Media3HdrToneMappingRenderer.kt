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
import androidx.media3.common.Tracks
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

internal fun Tracks.supportsHdrToneMapping(): Boolean? {
    var selectedVideo = false
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_VIDEO || !group.isSelected) continue
        for (index in 0 until group.length) {
            if (!group.isTrackSelected(index)) continue
            selectedVideo = true
            if (!group.getTrackFormat(index).supportsHdrToneMapping()) return false
        }
    }
    return true.takeIf { selectedVideo }
}

private fun Format.supportsHdrToneMapping(): Boolean = colorInfo?.colorSpace == C.COLOR_SPACE_BT2020 && ColorInfo.isTransferHdr(colorInfo) &&
    (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION || MediaCodecUtil.getAlternativeCodecMimeType(this) != null)

internal fun Format.withHdrToneMappingColorRange(decodedRange: Int = Format.NO_VALUE): Format {
    val color = colorInfo ?: return this
    if (!supportsHdrToneMapping() || color.colorRange == C.COLOR_RANGE_LIMITED || color.colorRange == C.COLOR_RANGE_FULL) {
        return this
    }
    // HDR tracks can leave the range in the in-band SPS. Media3's video graph treats
    // incomplete ColorInfo as SDR. Use the decoder's range when available, or limited range.
    val range = if (decodedRange == MediaFormat.COLOR_RANGE_FULL) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED
    return buildUpon().setColorInfo(color.buildUpon().setColorRange(range).build()).build()
}

private const val MPCVR_SDR_WHITE_NITS = 203

internal fun mpcvrPeakNits(hdrStaticInfo: ByteBuffer?): Int? {
    // Android's 25-byte CTA-861.3 Type 1 descriptor stores mastering max and MaxCLL as LE uint16.
    if (hdrStaticInfo == null || hdrStaticInfo.remaining() < 25) return null
    val descriptor = hdrStaticInfo.slice().order(ByteOrder.LITTLE_ENDIAN)
    if (descriptor.get(0).toInt() != 0) return null
    val maxCll = descriptor.getShort(21).toInt() and 0xffff
    val masteringMax = descriptor.getShort(17).toInt() and 0xffff
    return (maxCll.takeIf { it > 10 } ?: masteringMax.takeIf { it > 10 })?.coerceIn(MPCVR_SDR_WHITE_NITS + 1, 10000)
}

@UnstableApi
internal class Media3HdrToneMappingRenderer(
    builder: Builder,
    private val piecewise: Boolean,
    private val wideColor: Boolean,
    private val mpcvr: Boolean,
) : MediaCodecVideoRenderer(builder) {
    @Volatile
    private var contentPeakNits: Int? = null

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val selector = toneMappingDecoderSelector(mediaCodecSelector, format)
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
    ): List<MediaCodecInfo> = super.getDecoderInfos(toneMappingDecoderSelector(mediaCodecSelector, format), format, requiresSecureDecoder)

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
                UnsupportedOperationException("HDR tone mapping needs a compatible Dolby Vision HDR base layer"),
                format,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            )
        }
        if (mpcvr) contentPeakNits = mpcvrPeakNits(format.colorInfo?.hdrStaticInfo?.let(ByteBuffer::wrap))
        return super.maybeInitializeProcessingPipeline(format.withHdrToneMappingColorRange())
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val decodedRange = mediaFormat?.getInteger(MediaFormat.KEY_COLOR_RANGE, Format.NO_VALUE) ?: Format.NO_VALUE
        // Some files provide static HDR metadata only in-band. The decoder can expose
        // it here, before super registers the first stream and the GL sampler is built.
        if (mpcvr && contentPeakNits == null) {
            contentPeakNits = mpcvrPeakNits(mediaFormat?.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO))
        }
        super.onOutputFormatChanged(format.withHdrToneMappingColorRange(decodedRange), mediaFormat)
    }

    // A native Dolby Vision decoder can process the RPU itself. Use the compatible base
    // decoder so the OpenGL sampler receives the PQ or HLG signal described by the track.
    private fun toneMappingDecoderSelector(selector: MediaCodecSelector, format: Format): MediaCodecSelector {
        if (!hasCompatibleHdrBase(format)) return selector
        return MediaCodecSelector { mimeType, secure, tunneling ->
            if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) emptyList() else selector.getDecoderInfos(mimeType, secure, tunneling)
        }
    }

    private fun hasCompatibleHdrBase(format: Format): Boolean = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && format.supportsHdrToneMapping()

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl,
    ): PlaybackVideoGraphWrapper = PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
        .setEnablePlaylistMode(true)
        .setClock(clock)
        .setVideoGraphFactory(object : VideoGraph.Factory {
            override fun supportsMultipleInputs() = false

            override fun create(
                context: Context,
                outputColorInfo: ColorInfo,
                debugViewProvider: DebugViewProvider,
                listener: VideoGraph.Listener,
                listenerExecutor: Executor,
                initialTimestampOffsetUs: Long,
                renderFramesAutomatically: Boolean,
            ): VideoGraph = SingleInputVideoGraph.Factory(
                DefaultVideoFrameProcessor.Factory.Builder()
                    .apply { if (wideColor) setGlObjectsProvider(Media3WideColorGlObjects()) }
                    .build(),
            ).create(
                HdrToneMappingShaderContext(context.applicationContext, piecewise, wideColor, if (mpcvr) ::mpcvrShaderPeak else null),
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

    private fun mpcvrShaderPeak() = contentPeakNits ?: 1000
}

/**
 * Media3 applies its HDR-to-SDR curve in the input sampler before the video effects run.
 * Insert the chosen curve and retain dark HDR values in the pinned Media3 shaders.
 */
@Suppress("DEPRECATION")
private class HdrToneMappingShaderContext(
    context: Context,
    piecewise: Boolean,
    wideColor: Boolean,
    mpcvrPeakNits: (() -> Int)?,
) : ContextWrapper(context) {
    private val base = context.resources
    private val toneMappingShader by lazy {
        val resource = if (mpcvrPeakNits != null) R.raw.media3_mpcvr_tone_mapping else R.raw.media3_skiv_tone_mapping
        base.openRawResource(resource).bufferedReader().use { it.readText() }
    }
    private val displayP3Shader by lazy {
        base.openRawResource(R.raw.media3_display_p3).bufferedReader().use { it.readText() }
    }
    private val patchedResources = object : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun openRawResource(id: Int): InputStream {
            val hdrSampler = id == EffectResources.raw.fragment_shader_transformation_external_yuv_es3 ||
                id == EffectResources.raw.fragment_shader_transformation_hdr_internal_es3
            val sdrSampler = wideColor && (
                id == EffectResources.raw.fragment_shader_transformation_sdr_external_es2 ||
                    id == EffectResources.raw.fragment_shader_transformation_sdr_internal_es2
                )
            val copy = wideColor && id == EffectResources.raw.fragment_shader_copy_es2
            if (!hdrSampler && !sdrSampler && !copy) return base.openRawResource(id)

            // PQ values below about 0.61 nits can be flushed to zero on GPUs that
            // use native half precision for the sampler's mediump linear RGB.
            val source = base.openRawResource(id).bufferedReader().use { it.readText() }
                .replace("precision mediump float;", "precision highp float;")
                .replace("uniform __samplerExternal2DY2YEXT ", "uniform highp __samplerExternal2DY2YEXT ")
                .replace("uniform samplerExternalOES ", "uniform highp samplerExternalOES ")
                .replace("uniform sampler2D ", "uniform highp sampler2D ")
            if (copy) return source.byteInputStream()
            if (sdrSampler) {
                // Preserve SDR colors by converting the encoded RGB into the same P3 surface space.
                return source.replace("void main() {", "$displayP3Shader\nvoid main() {")
                    .replace("convertToOutputColors(transformedColors.rgb)", "srgbToDisplayP3(convertToOutputColors(transformedColors.rgb))")
                    .byteInputStream()
            }

            val function = "highp vec3 applyBt2020ToBt709Ootf(highp vec3 linearRgbBt2020) {"
            val start = source.indexOf(function)
            val end = if (start >= 0) source.indexOf("\n}", start) else -1
            check(end >= 0) { "Media3 HDR sampler shader has changed" }
            val mode = if (mpcvrPeakNits != null) {
                "#define MPCVR_PEAK_NITS ${mpcvrPeakNits()}\n" +
                    "#define MPCVR_SDR_WHITE_NITS $MPCVR_SDR_WHITE_NITS\n" +
                    "#define MPCVR_WCG ${if (wideColor) 1 else 0}"
            } else {
                "#define SKIV_PIECEWISE ${if (piecewise) 1 else 0}\n#define SKIV_WCG ${if (wideColor) 1 else 0}"
            }
            val toneMap = if (mpcvrPeakNits != null) "mpcvrToSdr" else "skivToSdr"
            val colorEncoding = if (wideColor) displayP3Shader else ""
            val shader = source.replaceRange(
                start,
                end + 2,
                "$mode\n$colorEncoding\n$toneMappingShader\n$function\n  return $toneMap(linearRgbBt2020);\n}",
            )
            return if (wideColor) {
                shader.replace("return gamma22Oetf(linearColor);", "return displayP3Encode(linearColor);").byteInputStream()
            } else {
                shader.byteInputStream()
            }
        }
    }

    override fun getResources(): Resources = patchedResources
}
