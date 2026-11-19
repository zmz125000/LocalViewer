package com.hippo.ehviewer.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** Display ASS dialogue using the same bottom-centered text layout as SubRip. */
@UnstableApi
internal class Media3SubtitleParserFactory(
    private val defaults: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory by defaults {
    override fun create(format: Format): SubtitleParser {
        val parser = defaults.create(format)
        if (format.sampleMimeType != MimeTypes.TEXT_SSA) return parser
        return object : SubtitleParser {
            override fun getCueReplacementBehavior(): Int = parser.cueReplacementBehavior

            override fun reset() = parser.reset()

            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                parser.parse(data, offset, length, outputOptions) { sample ->
                    val text = sample.cues.mapNotNull { it.text?.toString() }.distinct().joinToString("\n")
                    val cues = if (text.isEmpty()) emptyList() else listOf(Cue.Builder().setText(text).build())
                    output.accept(CuesWithTiming(cues, sample.startTimeUs, sample.durationUs))
                }
            }
        }
    }
}
