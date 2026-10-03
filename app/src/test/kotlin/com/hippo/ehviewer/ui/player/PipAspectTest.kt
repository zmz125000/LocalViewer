package com.hippo.ehviewer.ui.player

import com.hippo.ehviewer.ui.PIP_ASPECT_MAX
import com.hippo.ehviewer.ui.PIP_ASPECT_MIN
import com.hippo.ehviewer.ui.pipAspectFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipAspectTest {
    @Test
    fun widescreenStaysInsidePlatformLimits() {
        val fraction = pipAspectFraction(1920, 1080, pixelWidthHeightRatio = 1f)
        val ratio = fraction!!.first / fraction.second.toFloat()
        assertEquals(1920f / 1080f, ratio, 0.01f)
        assertTrue(ratio in PIP_ASPECT_MIN..PIP_ASPECT_MAX)
    }

    @Test
    fun extremeWidthIsClamped() {
        val fraction = pipAspectFraction(10_000, 100, pixelWidthHeightRatio = 1f)
        val ratio = fraction!!.first / fraction.second.toFloat()
        assertEquals(PIP_ASPECT_MAX, ratio, 0.001f)
    }

    @Test
    fun unknownSizeHasNoAspect() {
        assertNull(pipAspectFraction(0, 1080, 1f))
        assertNull(pipAspectFraction(1920, 0, 1f))
    }
}
