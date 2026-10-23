package com.hippo.ehviewer.image.hdr

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Decode Apple's auxiliary HEVC into Android's logarithmic gain-map samples. */
internal object AppleGainMapDecode {
    class Frame(val pixels: IntArray, val width: Int, val height: Int)

    fun decode(gain: HeifContainer.AppleGainMap): Frame? {
        if (gain.width <= 0 || gain.height <= 0 || gain.headroom <= 1.01f || !gain.headroom.isFinite() || gain.tiles.isEmpty()) return null
        val cellW = gain.tiles.first().width
        val cellH = gain.tiles.first().height
        val pixels = IntArray(gain.width * gain.height)
        val tables = HashMap<Int, IntArray>()
        for ((index, tile) in gain.tiles.withIndex()) {
            // Video codecs can reject still-image auxiliary dimensions or expose no
            // readable output. The bundled decoder handles these without a video surface.
            val picture = HevcDecoder.decode(tile, preferSoftware = true) ?: return null
            val table = tables.getOrPut(picture.bitDepth) { gainTable(picture.bitDepth, gain.headroom) }
            val x0 = (index % gain.columns) * cellW
            val y0 = (index / gain.columns) * cellH
            val copyW = minOf(picture.width, gain.width - x0)
            val copyH = minOf(picture.height, gain.height - y0)
            if (copyW <= 0 || copyH <= 0) return null
            for (y in 0 until copyH) {
                for (x in 0 until copyW) {
                    val code = picture.y[y * picture.width + x].toInt() and 0xffff
                    pixels[(y0 + y) * gain.width + x0 + x] = table[code.coerceAtMost(table.lastIndex)]
                }
            }
        }
        return rotate(pixels, gain.width, gain.height, gain.rotationDegreesCcw)
    }

    internal fun gainTable(bitDepth: Int, headroom: Float): IntArray {
        require(bitDepth in 8..16 && headroom > 1f && headroom.isFinite())
        val maxCode = (1 shl bitDepth) - 1
        val logHeadroom = ln(headroom)
        return IntArray(maxCode + 1) { code ->
            // Use the decoder's actual precision, never infer it from the brightest
            // pixel: a dark 10-bit map may contain no sample above 255.
            val v = code.toFloat() / maxCode
            val linear = if (v < 0.081f) v / 4.5f else ((v + 0.099f) / 1.099f).pow(1f / 0.45f)
            val mapped = ln(1f + (headroom - 1f) * linear) / logHeadroom
            val q = (mapped * 255f).roundToInt().coerceIn(0, 255)
            (0xff shl 24) or (q shl 16) or (q shl 8) or q
        }
    }

    private fun rotate(src: IntArray, width: Int, height: Int, degreesCcw: Int): Frame {
        val turns = ((degreesCcw % 360) + 360) % 360
        if (turns == 0) return Frame(src, width, height)
        val dstW = if (turns == 180) width else height
        val dstH = if (turns == 180) height else width
        val dst = IntArray(dstW * dstH)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx: Int
                val dy: Int
                when (turns) {
                    90 -> {
                        dx = y
                        dy = width - 1 - x
                    }
                    180 -> {
                        dx = width - 1 - x
                        dy = height - 1 - y
                    }
                    else -> {
                        dx = height - 1 - y
                        dy = x
                    }
                }
                dst[dy * dstW + dx] = src[y * width + x]
            }
        }
        return Frame(dst, dstW, dstH)
    }
}
