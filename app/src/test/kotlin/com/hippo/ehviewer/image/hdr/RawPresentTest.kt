package com.hippo.ehviewer.image.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPresentTest {
    @Test
    fun hdrOnIgnoresDeepColorAndUsesHdrWhenThePanelCan() {
        assertEquals(RawPresent.Hdr, rawPresentMode(hdrDisplay = true, advancedColor = false, panelHdr = true))
        assertEquals(RawPresent.Hdr, rawPresentMode(hdrDisplay = true, advancedColor = true, panelHdr = true))
        assertTrue(rawIsHdrContent(RawPresent.Hdr))
        assertTrue(rawIsWideGamut(RawPresent.Hdr))
        assertEquals(2.5f, rawContentBoost(RawPresent.Hdr, peakOverWhite = 2.5f, panelBoost = 4f), 0.001f)
        assertTrue(rawContentBoost(RawPresent.Hdr, peakOverWhite = 2.5f, panelBoost = 4f) > 1f)
    }

    @Test
    fun hdrOnWithoutPanelStaysDeepColor() {
        assertEquals(RawPresent.DeepColor, rawPresentMode(hdrDisplay = true, advancedColor = false, panelHdr = false))
        assertEquals(RawPresent.DeepColor, rawPresentMode(hdrDisplay = true, advancedColor = true, panelHdr = false))
        assertFalse(rawIsHdrContent(RawPresent.DeepColor))
        assertTrue(rawIsWideGamut(RawPresent.DeepColor))
        assertEquals(1f, rawContentBoost(RawPresent.DeepColor, peakOverWhite = 3f, panelBoost = 4f), 0.001f)
    }

    @Test
    fun deepColorAndEightBitFollowAdvancedColorOnlyWhenHdrIsOff() {
        assertEquals(RawPresent.DeepColor, rawPresentMode(hdrDisplay = false, advancedColor = true, panelHdr = true))
        assertEquals(RawPresent.EightBit, rawPresentMode(hdrDisplay = false, advancedColor = false, panelHdr = true))
        assertFalse(rawIsHdrContent(RawPresent.EightBit))
        assertFalse(rawIsWideGamut(RawPresent.EightBit))
        assertEquals(1f, rawContentBoost(RawPresent.EightBit, peakOverWhite = 4f, panelBoost = 4f), 0.001f)
    }

    @Test
    fun hdrBoostIsCappedByThePanel() {
        assertEquals(4f, rawContentBoost(RawPresent.Hdr, peakOverWhite = 8f, panelBoost = 4f), 0.001f)
        assertEquals(1f, rawContentBoost(RawPresent.Hdr, peakOverWhite = 1f, panelBoost = 4f), 0.001f)
    }

    @Test
    fun missingBaselineReservesTwoStopsOnlyForHdr() {
        assertEquals(4f, rawLinearSample(1f, -999f, 0f, 0f, cap = 8f, hdr = true), 0.001f)
        assertEquals(1f, rawLinearSample(0.25f, -999f, 0f, 0f, cap = 8f, hdr = true), 0.001f)
        assertEquals(0.5f, rawLinearSample(0.5f, -999f, 0f, 0f, cap = 1f, hdr = false), 0.001f)
    }

    @Test
    fun baselineExposurePlacesTheClipAndHighlightStopsLeaveShadows() {
        assertEquals(2.828427f, rawLinearSample(1f, 1.5f, 0f, 0f, cap = 8f, hdr = true), 0.001f)
        val below = rawLinearSample(0.25f, 1.5f, 0f, 0f, cap = 8f, hdr = true)
        assertEquals(below, rawLinearSample(0.25f, 1.5f, 0f, 1f, cap = 8f, hdr = true), 0.0001f)
        assertTrue(below < 1f)
        assertEquals(3f, rawLinearSample(0.75f, 2f, 0f, 0f, cap = 8f, hdr = true), 0.001f)
        assertEquals(2f, rawLinearSample(0.75f, 2f, 0f, 1f, cap = 8f, hdr = true), 0.001f)
    }

    @Test
    fun linearSampleClampsToTheCapAndRejectsNonFiniteInput() {
        assertEquals(2f, rawLinearSample(1f, -999f, 0f, 0f, cap = 2f, hdr = true), 0.001f)
        assertEquals(1f, rawLinearSample(1f, 1.5f, 0f, 0f, cap = 1f, hdr = false), 0.001f)
        assertEquals(0f, rawLinearSample(Float.NaN, 0f, 0f, 0f, cap = 8f, hdr = true), 0f)
        assertEquals(0f, rawLinearSample(1f, 0f, Float.NaN, 0f, cap = 8f, hdr = true), 0f)
        assertEquals(0f, rawLinearSample(1f, 0f, 0f, Float.NaN, cap = 8f, hdr = true), 0f)
        assertEquals(0f, rawLinearSample(1f, 0f, 0f, 0f, cap = Float.NaN, hdr = true), 0f)
    }
}
