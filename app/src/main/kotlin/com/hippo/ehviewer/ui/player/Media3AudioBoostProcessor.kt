package com.hippo.ehviewer.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

@UnstableApi
internal class Media3AudioBoostProcessor(gainDb: Int) : BaseAudioProcessor() {
    @Volatile
    private var gain = toLinearGain(gainDb)

    fun setGainDb(gainDb: Int) {
        gain = toLinearGain(gainDb)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val multiplier = gain
        if (multiplier == 1f) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.hasRemaining()) {
                val sample = (inputBuffer.short * multiplier).roundToInt()
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        output.flip()
    }

    private fun toLinearGain(gainDb: Int): Float = 10f.pow(gainDb / 20f)
}
