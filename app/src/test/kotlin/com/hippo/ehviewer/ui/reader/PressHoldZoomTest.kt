package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressHoldZoomTest {
    @Test
    fun pressHoldZoomFactorMapsOffAndSupportedSteps() {
        assertEquals(0f, pressHoldZoomFactor(0), 0f)
        assertEquals(0f, pressHoldZoomFactor(100), 0f)
        assertEquals(1.1f, pressHoldZoomFactor(110), 0.0001f)
        assertEquals(1.2f, pressHoldZoomFactor(120), 0.0001f)
        assertEquals(1.3f, pressHoldZoomFactor(130), 0.0001f)
        assertEquals(1.4f, pressHoldZoomFactor(140), 0.0001f)
        assertEquals(1.5f, pressHoldZoomFactor(150), 0.0001f)
        assertEquals(listOf(0, 110, 120, 130, 140, 150), PRESS_HOLD_ZOOM_VALUES)
    }

    @Test
    fun pressHoldZoomTimeoutHalvesLongPressTimeout() {
        assertEquals(200L, pressHoldZoomTimeoutMillis(400L))
        assertEquals(250L, pressHoldZoomTimeoutMillis(500L))
        assertEquals(1L, pressHoldZoomTimeoutMillis(0L))
    }

    @Test
    fun isZoomedForPressHoldDetectsMinimum11xRegardlessOfInitialScale() {
        assertFalse(isZoomedForPressHold(currentScale = 1f, initialScale = 1f))
        assertFalse(isZoomedForPressHold(currentScale = 2f, initialScale = 2f))
        assertFalse(isZoomedForPressHold(currentScale = 1f, initialScale = 0f))
        assertTrue(isZoomedForPressHold(currentScale = 1.1f, initialScale = 1f))
        assertTrue(isZoomedForPressHold(currentScale = 2.2f, initialScale = 2f))
        assertTrue(isZoomedForPressHold(currentScale = 0.55f, initialScale = 0.5f))
    }
}
