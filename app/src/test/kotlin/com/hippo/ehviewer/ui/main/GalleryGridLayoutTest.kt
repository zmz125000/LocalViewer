package com.hippo.ehviewer.ui.main

import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PHONE_SW_DP = 411
private val TABLET_SW_DP = WIDTH_DP_MEDIUM_LOWER_BOUND

class GalleryGridLayoutTest {
    @Test
    fun phonePortrait() {
        val layout = windowListLayout(PHONE_SW_DP, landscape = false)
        assertEquals(1, layout.columns)
        assertTrue(layout.capReaderSheet)
    }

    @Test
    fun phoneLandscape() {
        val layout = windowListLayout(PHONE_SW_DP, landscape = true)
        assertEquals(2, layout.columns)
        assertFalse(layout.capReaderSheet)
    }

    @Test
    fun tabletPortrait() {
        val layout = windowListLayout(TABLET_SW_DP, landscape = false)
        assertEquals(2, layout.columns)
        assertTrue(layout.capReaderSheet)
    }

    @Test
    fun tabletLandscape() {
        val layout = windowListLayout(TABLET_SW_DP, landscape = true)
        assertEquals(3, layout.columns)
        assertTrue(layout.capReaderSheet)
    }
}
