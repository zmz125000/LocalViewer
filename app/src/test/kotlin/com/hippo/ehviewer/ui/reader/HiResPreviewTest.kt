package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiResPreviewTest {
    @Test
    fun restingFitUsesPreview() {
        // Landscape phone, 4:3 frame fitted to ~1440px, preview long edge 4096.
        assertTrue(shouldDrawHiResPreview(destLongPx = 1440f, layerScale = 1f, previewLongPx = 4096))
    }

    @Test
    fun pinchPastPreviewUsesOriginal() {
        assertFalse(shouldDrawHiResPreview(destLongPx = 1440f, layerScale = 3f, previewLongPx = 4096))
    }

    @Test
    fun unspecifiedScaleUsesPreview() {
        assertTrue(shouldDrawHiResPreview(destLongPx = 1440f, layerScale = 0f, previewLongPx = 4096))
        assertTrue(shouldDrawHiResPreview(destLongPx = 1440f, layerScale = Float.NaN, previewLongPx = 4096))
    }

    @Test
    fun missingPreviewNeverDraws() {
        assertFalse(shouldDrawHiResPreview(destLongPx = 1440f, layerScale = 1f, previewLongPx = 0))
    }
}
