package com.hippo.ehviewer.ui.player

import android.content.Context
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.hippo.ehviewer.Settings

/** PCM modes disable encoded passthrough so the mixer actually receives decoded samples. */
@UnstableApi
internal class Media3AudioRenderersFactory(context: Context, private val mode: Int) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (mode == Settings.MEDIA3_AUDIO_PASSTHROUGH) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }

        // The no-context builder uses PCM-only capabilities. The context builder tracks
        // encoded output capabilities and can bypass PCM audio processors entirely.
        @Suppress("DEPRECATION")
        val builder = DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)

        val outputChannels = when (mode) {
            Settings.MEDIA3_AUDIO_STEREO -> 2
            Settings.MEDIA3_AUDIO_DEVICE -> AudioCapabilities.getCapabilities(context)
                .maxChannelCount.coerceIn(2, 8)
            else -> 0
        }
        if (outputChannels > 0) {
            val mixer = ChannelMixingAudioProcessor()
            for (inputChannels in 1..16) {
                val matrix = when {
                    inputChannels <= outputChannels -> ChannelMixingMatrix.createForConstantGain(inputChannels, inputChannels)
                    inputChannels in 3..5 && outputChannels == 2 ->
                        ChannelMixingMatrix.createForConstantPower(inputChannels, 2)
                    inputChannels == 6 && outputChannels == 2 -> surroundToStereo(6)
                    inputChannels == 8 && outputChannels == 2 -> surroundToStereo(8)
                    else -> fallbackMatrix(inputChannels, outputChannels)
                }
                mixer.putChannelMixingMatrix(matrix)
            }
            builder.setAudioProcessors(arrayOf(mixer))
        }
        return builder.build()
    }

    private fun surroundToStereo(channels: Int): ChannelMixingMatrix {
        val coefficients = FloatArray(channels * 2)
        fun channel(index: Int, left: Float, right: Float) {
            coefficients[index * 2] = left
            coefficients[index * 2 + 1] = right
        }
        // Android/FFmpeg PCM order: front L/R, center, LFE, rear L/R, side L/R.
        channel(0, 0.5f, 0f)
        channel(1, 0f, 0.5f)
        channel(2, 0.35f, 0.35f)
        channel(3, 0.15f, 0.15f)
        channel(4, if (channels == 6) 0.35f else 0.25f, 0f)
        channel(5, 0f, if (channels == 6) 0.35f else 0.25f)
        if (channels == 8) {
            channel(6, 0.25f, 0f)
            channel(7, 0f, 0.25f)
        }
        return ChannelMixingMatrix(channels, 2, coefficients)
    }

    private fun fallbackMatrix(inputChannels: Int, outputChannels: Int): ChannelMixingMatrix {
        val coefficients = FloatArray(inputChannels * outputChannels)
        val gain = outputChannels.toFloat() / inputChannels
        for (input in 0 until inputChannels) {
            coefficients[input * outputChannels + (input % outputChannels)] = gain
        }
        return ChannelMixingMatrix(inputChannels, outputChannels, coefficients)
    }
}
