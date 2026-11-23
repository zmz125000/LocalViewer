package com.hippo.ehviewer.ui.player

import androidx.media3.common.audio.AudioMixingUtil
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Channel mixer that always consumes all bytes from [queueInput].
 *
 * Media3's [androidx.media3.common.audio.ChannelMixingAudioProcessor] leaves trailing bytes when
 * `inputBuffer.remaining() % bytesPerFrame != 0`. Once fewer than `bytesPerFrame` bytes remain,
 * `DefaultAudioSink.processBuffers()` loops forever calling `queueInput()` with 0 frames to mix
 * while `AudioMixingUtil.mix()` allocates fresh frame arrays on every iteration.
 */
@UnstableApi
internal class Media3ChannelMixingAudioProcessor : BaseAudioProcessor() {
    private val matrixByInputChannelCount = HashMap<Int, ChannelMixingMatrix>()
    private var pendingFrame = ByteArray(0)
    private var pendingFrameSize = 0

    fun putChannelMixingMatrix(matrix: ChannelMixingMatrix) {
        matrixByInputChannelCount[matrix.inputChannelCount] = matrix
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!AudioMixingUtil.canMix(inputAudioFormat)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val matrix = matrixByInputChannelCount[inputAudioFormat.channelCount]
            ?: throw AudioProcessor.UnhandledAudioFormatException(
                "No mixing matrix set for input channel count=${inputAudioFormat.channelCount}.",
                inputAudioFormat,
            )
        if (matrix.isIdentity) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            matrix.outputChannelCount,
            inputAudioFormat.encoding,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val matrix = checkNotNull(matrixByInputChannelCount[inputAudioFormat.channelCount])
        val inFrameSize = inputAudioFormat.bytesPerFrame
        val outFrameSize = outputAudioFormat.bytesPerFrame
        val remaining = inputBuffer.remaining()

        // Fast path: no buffered partial frame and input is frame-aligned.
        if (pendingFrameSize == 0 && remaining % inFrameSize == 0) {
            val frames = remaining / inFrameSize
            val output = replaceOutputBuffer(frames * outFrameSize)
            AudioMixingUtil.mix(
                inputBuffer,
                inputAudioFormat,
                output,
                outputAudioFormat,
                matrix,
                frames,
                /* accumulate = */
                false,
                /* clipFloatOutput = */
                true,
            )
            output.flip()
            return
        }

        if (pendingFrame.size < inFrameSize) {
            pendingFrame = ByteArray(inFrameSize)
        }

        var completedPendingFrame = false
        if (pendingFrameSize > 0) {
            val needed = inFrameSize - pendingFrameSize
            val toCopy = minOf(needed, inputBuffer.remaining())
            inputBuffer.get(pendingFrame, pendingFrameSize, toCopy)
            pendingFrameSize += toCopy
            if (pendingFrameSize < inFrameSize) {
                return
            }
            completedPendingFrame = true
        }

        val directFrames = inputBuffer.remaining() / inFrameSize
        val totalFrames = directFrames + if (completedPendingFrame) 1 else 0
        if (totalFrames > 0) {
            val output = replaceOutputBuffer(totalFrames * outFrameSize)
            if (completedPendingFrame) {
                val frameBuffer = ByteBuffer.wrap(pendingFrame, 0, inFrameSize).order(inputBuffer.order())
                AudioMixingUtil.mix(
                    frameBuffer,
                    inputAudioFormat,
                    output,
                    outputAudioFormat,
                    matrix,
                    /* framesToMix = */
                    1,
                    /* accumulate = */
                    false,
                    /* clipFloatOutput = */
                    true,
                )
                pendingFrameSize = 0
            }
            if (directFrames > 0) {
                AudioMixingUtil.mix(
                    inputBuffer,
                    inputAudioFormat,
                    output,
                    outputAudioFormat,
                    matrix,
                    directFrames,
                    /* accumulate = */
                    false,
                    /* clipFloatOutput = */
                    true,
                )
            }
            output.flip()
        }

        val trailing = inputBuffer.remaining()
        if (trailing > 0) {
            inputBuffer.get(pendingFrame, 0, trailing)
            pendingFrameSize = trailing
        }
    }

    override fun onQueueEndOfStream() {
        pendingFrameSize = 0
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        pendingFrameSize = 0
    }

    override fun onReset() {
        pendingFrame = ByteArray(0)
        pendingFrameSize = 0
    }
}
