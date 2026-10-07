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
}
