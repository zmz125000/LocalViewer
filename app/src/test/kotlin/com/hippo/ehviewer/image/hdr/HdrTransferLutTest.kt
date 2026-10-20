package com.hippo.ehviewer.image.hdr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrTransferLutTest {
    @Test
    fun sdrTransfersStaySdrAndRetainLowCodePrecision() {
        for (transfer in listOf(1, 4, 5, 6, 8, 13)) {
            val lut = HdrTransferLut(transfer)
            assertEquals(0f, lut.linear(0f), 0f)
            assertEquals(1f, lut.linear(1f), 0.00001f)
            assertTrue(lut.linear(1f / 1023f) > 0f)
            assertTrue(lut.linear(2f / 1023f) > lut.linear(1f / 1023f))
        }
        assertEquals(0.5f, HdrTransferLut(8).linear(0.5f), 0f)
        assertEquals(0.21404114f, HdrTransferLut(13).linear(0.5f), 0.00001f)
    }

    @Test
    fun matchesAnalyticPqAndHlgBetweenTableEntries() {
        for (transfer in listOf(16, 18)) {
            val lut = HdrTransferLut(transfer)
            var previous = 0f
            for (i in 0..262144) {
                val code = i / 262144f
                val expected = analytic(code, transfer)
                val actual = lut.linear(code)
                val error = abs(actual - expected) / max(0.0001f, expected)
                assertTrue("transfer=$transfer code=$code error=$error", error < 0.00001f)
                assertTrue("transfer=$transfer must be monotonic", actual >= previous)
                previous = actual
            }
        }
    }

    @Test
    fun clampsMatrixExcursionsAndPreservesHdrHeadroom() {
        for (transfer in listOf(16, 18)) {
            val lut = HdrTransferLut(transfer)
            assertEquals(0f, lut.linear(-1f), 0f)
            assertEquals(0f, lut.linear(0f), 0f)
            assertEquals(analytic(1f, transfer), lut.linear(1f), 0f)
            assertEquals(lut.linear(1f), lut.linear(2f), 0f)
            assertTrue(lut.linear(1f) > 1f)
        }
        assertEquals(10000f / 203f, HdrTransferLut(16).linear(1f), 0.00001f)
    }

    // Analytic reference from the previous per-pixel HEIC conversion.
    private fun analytic(code: Float, transfer: Int): Float {
        if (code <= 0f) return 0f
        if (transfer == 18) {
            val scene = if (code <= 0.5f) (code * code) / 3f else (exp((code - 0.55991073f) / 0.17883277f) + 0.28466892f) / 12f
            return scene * 12f
        }
        val np = code.toDouble().pow(1.0 / 78.84375)
        val num = (np - 0.8359375).coerceAtLeast(0.0)
        val den = 18.8515625 - 18.6875 * np
        val nits = if (den <= 1e-6) 10000f else (10000.0 * (num / den).pow(1.0 / 0.1593017578125)).toFloat()
        return nits / 203f
    }
}
