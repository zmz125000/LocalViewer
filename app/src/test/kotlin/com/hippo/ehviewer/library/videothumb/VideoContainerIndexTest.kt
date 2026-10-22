package com.hippo.ehviewer.library.videothumb

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoContainerIndexTest {
    @Test
    fun candidateTimesClampToDuration() {
        assertEquals(listOf(2_000_000L, 30_000_000L, 0L), VideoContainers.candidateTimesUs(null, 1_000L))
        assertEquals(listOf(2_000_000L, 7_200_000L, 0L), VideoContainers.candidateTimesUs(8_000_000L, 1_000L))
        assertEquals(
            listOf(120_000_000L, 30_000_000L, 2_000_000L, 0L),
            VideoContainers.candidateTimesUs(3_600_000_000L, 200L * 1024 * 1024),
        )
    }

    @Test
    fun h264SpsGeometryReadsAnamorphicAspect() {
        // x264 1440x1080i, SAR 4:3 (AVCHD HD), with an emulation-prevention byte.
        val sps = intArrayOf(
            0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x5A, 0x04, 0x4F, 0xDE, 0x1C, 0x20,
            0x00, 0x00, 0x03, 0x00, 0x20, 0x00, 0x00, 0x06, 0x43, 0xE2, 0xC5, 0xB2, 0xC0,
        ).map { it.toByte() }.toByteArray()
        val g = H264.parseSps(sps)!!
        assertEquals(1440, g.width)
        assertEquals(1080, g.height)
        assertEquals(4f / 3f, g.pixelAspect, 0.001f)
    }
}
