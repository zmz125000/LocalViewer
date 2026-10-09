package com.hippo.ehviewer.image

import org.junit.Assert.assertEquals
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
}