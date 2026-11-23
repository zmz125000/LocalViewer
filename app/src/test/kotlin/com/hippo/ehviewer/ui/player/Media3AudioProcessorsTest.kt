package com.hippo.ehviewer.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class Media3AudioProcessorsTest {
    @Test
    fun channelMixerDoesNotSpinOnUnalignedBufferInPipeline() {
        val mixer = Media3ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(6, 2))
        }
        val pipeline = AudioProcessingPipeline(ImmutableList.of(mixer))
        val outputFormat = pipeline.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        assertEquals(2, outputFormat.channelCount)
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue(pipeline.isOperational)

        // 640 bytes is 53 full 6ch 16-bit frames (636 bytes) + 4 trailing bytes (e.g. TrueHD 8ch packet in 6ch sink).
        val input = ByteBuffer.allocate(640).order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            input.putShort(1000)
        }
        input.flip()

        // Mirrors DefaultAudioSink.processBuffers(): loops while inputBuffer.hasRemaining().
        var iterations = 0
        var totalOutputBytes = 0
        while (!pipeline.isEnded) {
            while (true) {
                val out = pipeline.output
                if (!out.hasRemaining()) break
                totalOutputBytes += out.remaining()
                out.position(out.limit())
            }
            if (!input.hasRemaining()) break
            pipeline.queueInput(input)
            iterations++
            assertTrue("AudioProcessingPipeline spun without consuming input", iterations <= 4)
        }

        assertFalse(input.hasRemaining())
        assertEquals(53 * 2 * 2, totalOutputBytes)
    }

    @Test
    fun channelMixerReassemblesFrameSplitAcrossBuffers() {
        val matrix = ChannelMixingMatrix(
            6,
            2,
            floatArrayOf(
                0.5f, 0f,
                0f, 0.5f,
                0.35f, 0.35f,
                0.15f, 0.15f,
                0.35f, 0f,
                0f, 0.35f,
            ),
        )
        val wholeMixer = Media3ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(matrix) }
        val splitMixer = Media3ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(matrix) }
        val format = AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)
        wholeMixer.configure(format)
        wholeMixer.flush(AudioProcessor.StreamMetadata.DEFAULT)
        splitMixer.configure(format)
        splitMixer.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val samples = shortArrayOf(
            4000, -2000, 1000, 500, 2000, -1000,
            -3000, 6000, 1500, -500, 1000, 3000,
            500, 1500, -2000, 800, -1200, 2400,
        )
        val wholeInput = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (sample in samples) wholeInput.putShort(sample)
        wholeInput.flip()

        wholeMixer.queueInput(wholeInput)
        val expected = drainBytes(wholeMixer.output)
        assertEquals(3 * 4, expected.size)

        // Feed the exact same 36 bytes in unaligned chunks of 4, 4, 10, 18 bytes.
        val raw = wholeInput.array()
        val actual = ArrayList<Byte>()
        for ((offset, len) in listOf(0 to 4, 4 to 4, 8 to 10, 18 to 18)) {
            val chunk = ByteBuffer.wrap(raw, offset, len).order(ByteOrder.nativeOrder())
            splitMixer.queueInput(chunk)
            assertFalse("Chunk ($offset, $len) was not fully consumed", chunk.hasRemaining())
            for (b in drainBytes(splitMixer.output)) actual.add(b)
        }

        assertEquals(expected.toList(), actual)
    }

    @Test
    fun channelMixerFlushDiscardsPartialFrame() {
        val mixer = Media3ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(6, 2))
        }
        val format = AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)
        mixer.configure(format)
        mixer.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val partial = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        mixer.queueInput(partial)
        assertFalse(partial.hasRemaining())
        assertFalse(mixer.output.hasRemaining())

        mixer.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val fullFrame = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        mixer.queueInput(fullFrame)
        assertFalse(fullFrame.hasRemaining())
        assertEquals(4, mixer.output.remaining())
    }

    @Test
    fun channelMixerIdentityMatrixIsInactive() {
        val mixer = Media3ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2))
        }
        val out = mixer.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out)
        assertFalse(mixer.isActive)
    }

    @Test
    fun audioBoostConsumesOddSizedBufferWithoutThrowing() {
        val boost = Media3AudioBoostProcessor(gainDb = 6)
        boost.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        boost.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val oddInput = ByteBuffer.allocate(5).order(ByteOrder.nativeOrder())
        oddInput.putShort(1000)
        oddInput.putShort(-1000)
        oddInput.put(0x7f)
        oddInput.flip()

        boost.queueInput(oddInput)
        assertFalse(oddInput.hasRemaining())
        val out = boost.output
        assertEquals(4, out.remaining())
        assertTrue(out.short > 1000)
        assertTrue(out.short < -1000)
    }

    private fun drainBytes(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
