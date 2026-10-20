package com.hippo.ehviewer.image.hdr

import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleGainMapDecodeTest {
    @Test
    fun logarithmicMapRecoversAppleHighlightBoostAtAllPrecisions() {
        for (depth in listOf(8, 10, 12, 16)) {
            for (headroom in listOf(3.3551707f, 3.4822023f, 5.974755f, 7.371721f)) {
                val table = AppleGainMapDecode.gainTable(depth, headroom)
                assertEquals(1 shl depth, table.size)
                assertEquals(0xff000000.toInt(), table.first())
                assertEquals(0xffffffff.toInt(), table.last())
                var previous = 0
                for (code in table.indices) {
                    val q = table[code] and 255
                    assertTrue(q >= previous)
                    previous = q
                    val v = code.toFloat() / table.lastIndex
                    val linear = if (v < 0.081f) v / 4.5f else ((v + 0.099f) / 1.099f).pow(1f / 0.45f)
                    val appleBoost = 1f + (headroom - 1f) * linear
                    val androidBoost = headroom.pow(q / 255f)
                    assertTrue("$depth-bit code=$code", abs(androidBoost / appleBoost - 1f) < 0.005f)
                }
            }
        }
    }

    @Test
    fun darkTenBitMapIsNotMistakenForEightBit() {
        val eight = AppleGainMapDecode.gainTable(8, 7.37f)
        val ten = AppleGainMapDecode.gainTable(10, 7.37f)
        assertTrue((ten[255] and 255) < (eight[255] and 255) / 2)
    }
}
