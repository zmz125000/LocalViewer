package com.hippo.ehviewer.image.hdr

import kotlin.math.exp
import kotlin.math.pow

/** CICP transfer to linear light. HDR 1.0 is 203 nits; SDR white stays 1.0. */
internal class HdrTransferLut(transfer: Int) {
    private val table = when (transfer) {
        16 -> pqTable
        18 -> hlgTable
        8 -> linearTable
        1, 6, 14, 15 -> bt709Table
        4 -> gamma22Table
        5 -> gamma28Table
        else -> srgbTable
    }

    fun linear(code: Float): Float {
        if (code <= 0f) return 0f
        if (code >= 1f) return table[STEPS]
        val position = code * STEPS
        val index = position.toInt()
        val fraction = position - index
        return table[index] + (table[index + 1] - table[index]) * fraction
    }

    private companion object {
        // Interpolation keeps continuous RGB precision after the YUV matrix;
        // rounding directly to a 10/16-bit code would lose precision near black.
        const val STEPS = 65536
        val linearTable by lazy { FloatArray(STEPS + 1) { it.toFloat() / STEPS } }
        val srgbTable by lazy {
            FloatArray(STEPS + 1) {
                val x = it.toFloat() / STEPS
                if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
            }
        }
        val bt709Table by lazy {
            FloatArray(STEPS + 1) {
                val x = it.toFloat() / STEPS
                if (x < 4.5f * 0.018053968510807f) {
                    x / 4.5f
                } else {
                    ((x + 0.09929682680944f) / 1.09929682680944f).pow(1f / 0.45f)
                }
            }
        }
        val gamma22Table by lazy { FloatArray(STEPS + 1) { (it.toFloat() / STEPS).pow(2.2f) } }
        val gamma28Table by lazy { FloatArray(STEPS + 1) { (it.toFloat() / STEPS).pow(2.8f) } }
        val pqTable by lazy {
            FloatArray(STEPS + 1) { i ->
                if (i == 0) {
                    0f
                } else {
                    val n = i.toFloat() / STEPS
                    val np = n.toDouble().pow(1.0 / 78.84375)
                    val num = (np - 0.8359375).coerceAtLeast(0.0)
                    val den = 18.8515625 - 18.6875 * np
                    val nits = if (den <= 1e-6) 10000f else (10000.0 * (num / den).pow(1.0 / 0.1593017578125)).toFloat()
                    nits / 203f
                }
            }
        }
        val hlgTable by lazy {
            FloatArray(STEPS + 1) { i ->
                val x = i.toFloat() / STEPS
                val scene = if (x <= 0.5f) (x * x) / 3f else (exp((x - 0.55991073f) / 0.17883277f) + 0.28466892f) / 12f
                scene * 12f
            }
        }
    }
}
