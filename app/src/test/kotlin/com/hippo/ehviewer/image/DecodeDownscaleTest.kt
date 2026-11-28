package com.hippo.ehviewer.image

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeDownscaleTest {
    @Test
    fun catmullRomIsOneAtCenterAndZeroAtOne() {
        assertEquals(1f, decodeKernelWeight(0f, kernel = 4), 1e-5f)
        assertEquals(0f, decodeKernelWeight(1f, kernel = 4), 1e-5f)
    }

    @Test
    fun bilinearIsATent() {
        assertEquals(1f, decodeKernelWeight(0f, kernel = 2), 1e-5f)
        assertEquals(0.25f, decodeKernelWeight(0.75f, kernel = 2), 1e-5f)
        assertEquals(0f, decodeKernelWeight(1f, kernel = 2), 1e-5f)
    }

    @Test
    fun lanczosIsZeroOutsideItsWindow() {
        assertEquals(1f, decodeKernelWeight(0f, kernel = 6), 1e-4f)
        assertEquals(0f, decodeKernelWeight(3f, kernel = 6), 1e-5f)
    }

    @Test
    fun separableFilteredMatchesDirect2DReferenceAcrossAllKernels() {
        val srcW = 29
        val srcH = 23
        val dstW = 13
        val dstH = 11
        val src = IntArray(srcW * srcH) { idx ->
            val x = idx % srcW
            val y = idx / srcW
            val a = ((x * 13 + y * 7) and 0xFF)
            val r = ((x * 29 + y * 5) and 0xFF)
            val g = ((x * 11 + y * 19) and 0xFF)
            val b = ((x * 3 + y * 31) and 0xFF)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        for (kernel in 2..6) {
            val actual = filtered(src, srcW, srcH, dstW, dstH, kernel)
            val expected = direct2DReference(src, srcW, srcH, dstW, dstH, kernel)
            assertEquals(expected.size, actual.size)
            for (i in actual.indices) {
                val pa = actual[i]
                val pe = expected[i]
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    val ca = (pa ushr shift) and 0xFF
                    val ce = (pe ushr shift) and 0xFF
                    assertTrue(
                        "kernel=$kernel idx=$i shift=$shift actual=$ca expected=$ce",
                        abs(ca - ce) <= 1,
                    )
                }
            }
        }
    }

    private fun direct2DReference(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        kernel: Int,
    ): IntArray {
        val radius = when (kernel) {
            2 -> 1
            6 -> 3
            else -> 2
        }
        val span = radius * 2
        fun buildTaps(dst: Int, srcSize: Int): Pair<IntArray, FloatArray> {
            val index = IntArray(dst * span)
            val weight = FloatArray(dst * span)
            for (d in 0 until dst) {
                val p = (d + 0.5f) * srcSize / dst - 0.5f
                val base = floor(p).toInt() - (radius - 1)
                val at = d * span
                var sum = 0f
                for (i in 0 until span) {
                    val ix = base + i
                    val w = decodeKernelWeight(p - ix, kernel)
                    index[at + i] = ix.coerceIn(0, srcSize - 1)
                    weight[at + i] = w
                    sum += w
                }
                if (sum != 0f) {
                    for (i in 0 until span) weight[at + i] /= sum
                }
            }
            return index to weight
        }
        val (xIdx, xW) = buildTaps(dstW, srcW)
        val (yIdx, yW) = buildTaps(dstH, srcH)
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until span) {
                    val sy = yIdx[y * span + j]
                    val wy = yW[y * span + j]
                    for (i in 0 until span) {
                        val sx = xIdx[x * span + i]
                        val w = wy * xW[x * span + i]
                        val pixel = src[sy * srcW + sx]
                        a += ((pixel ushr 24) and 0xFF) * w
                        r += ((pixel ushr 16) and 0xFF) * w
                        g += ((pixel ushr 8) and 0xFF) * w
                        b += (pixel and 0xFF) * w
                    }
                }
                out[y * dstW + x] = (a.roundToInt().coerceIn(0, 255) shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
