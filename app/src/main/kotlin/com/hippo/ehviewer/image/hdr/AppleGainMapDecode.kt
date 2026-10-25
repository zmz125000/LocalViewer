package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Decode Apple's auxiliary HEVC into Android's logarithmic gain-map samples. */
internal object AppleGainMapDecode {
    fun decode(gain: HeifContainer.AppleGainMap): Bitmap? {
        if (gain.width <= 0 || gain.height <= 0 || gain.headroom <= 1.01f || !gain.headroom.isFinite() || gain.tiles.isEmpty()) return null
        val cellW = gain.tiles.first().width
        val cellH = gain.tiles.first().height
        val turns = ((gain.rotationDegreesCcw % 360) + 360) % 360
        val outW = if (turns == 0 || turns == 180) gain.width else gain.height
        val outH = if (turns == 0 || turns == 180) gain.height else gain.width
        NativeStillBuffer.allocate(outW.toLong() * outH * 4).use { pixels ->
            val tables = HashMap<Int, IntArray>()
            for ((index, tile) in gain.tiles.withIndex()) {
                // Video codecs can reject still-image auxiliary dimensions or expose no
                // readable output. The bundled decoder handles these without a video surface.
                val picture = HevcDecoder.decode(tile, preferSoftware = true) ?: return null
                picture.use {
                    val table = tables.getOrPut(picture.bitDepth) { gainTable(picture.bitDepth, gain.headroom) }
                    val x0 = (index % gain.columns) * cellW
                    val y0 = (index / gain.columns) * cellH
                    val copyW = minOf(picture.width, gain.width - x0)
                    val copyH = minOf(picture.height, gain.height - y0)
                    if (copyW <= 0 || copyH <= 0) return null
                    for (y in 0 until copyH) {
                        for (x in 0 until copyW) {
                            val code = picture.y[y * picture.width + x].toInt() and 0xffff
                            val sx = x0 + x
                            val sy = y0 + y
                            val dx = when (turns) {
                                90 -> sy
                                180 -> gain.width - 1 - sx
                                270 -> gain.height - 1 - sy
                                else -> sx
                            }
                            val dy = when (turns) {
                                90 -> gain.width - 1 - sx
                                180 -> gain.height - 1 - sy
                                270 -> sx
                                else -> sy
                            }
                            pixels.buffer.putInt((dy * outW + dx) * 4, table[code.coerceAtMost(table.lastIndex)])
                        }
                    }
                }
            }
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(pixels.buffer)
                return bitmap
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }
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
}
