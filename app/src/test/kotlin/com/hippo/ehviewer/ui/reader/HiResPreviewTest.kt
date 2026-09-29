package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiResPreviewTest {
    @Test
    fun restingFitStaysOnPreviewDecode() {
        // Landscape phone, 4:3 frame fitted to ~1440px.
        assertFalse(zoomPastHiResPreview(destLongPx = 1440f, layerScale = 1f))
    }

    @Test
    fun pinchPast4096RequestsFullDecode() {
        assertTrue(zoomPastHiResPreview(destLongPx = 1440f, layerScale = 3f))
    }

    @Test
    fun unknownScaleDoesNotUpgrade() {
        assertFalse(zoomPastHiResPreview(destLongPx = 1440f, layerScale = 0f))
        assertFalse(zoomPastHiResPreview(destLongPx = 1440f, layerScale = Float.NaN))
    }
}
