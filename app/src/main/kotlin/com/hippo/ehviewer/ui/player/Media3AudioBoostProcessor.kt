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
        val remaining = inputBuffer.remaining()
        val alignedBytes = remaining and 1.inv()
        val output = replaceOutputBuffer(alignedBytes)
        val multiplier = gain
        if (multiplier == 1f) {
            if (alignedBytes == remaining) {
                output.put(inputBuffer)
            } else {
                val limit = inputBuffer.limit()
                inputBuffer.limit(inputBuffer.position() + alignedBytes)
                output.put(inputBuffer)
                inputBuffer.limit(limit)
                inputBuffer.position(limit)
            }
        } else {
            val end = inputBuffer.position() + alignedBytes
            while (inputBuffer.position() < end) {
                val sample = (inputBuffer.short * multiplier).roundToInt()
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            if (inputBuffer.hasRemaining()) {
                inputBuffer.position(inputBuffer.limit())
            }
        }
        output.flip()
    }

    private fun toLinearGain(gainDb: Int): Float = 10f.pow(gainDb / 20f)
}
