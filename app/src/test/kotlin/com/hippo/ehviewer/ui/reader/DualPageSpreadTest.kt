package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPageSpreadTest {
    @Test
    fun `narrow pair uses full height and centers width`() {
        val viewport = Size(1920f, 1080f)
        val fitted = fitSpreadSize(1.4f, viewport)
        assertEquals(1080f * 1.4f, fitted.width, 0.5f)
        assertEquals(1080f, fitted.height, 0.01f)
        assertTrue(fitted.width < viewport.width)
    }

    @Test
    fun `wide pair uses full width and shrinks height`() {
        val viewport = Size(1920f, 1080f)
        val fitted = fitSpreadSize(2.4f, viewport)
        assertEquals(1920f, fitted.width, 0.01f)
        assertEquals(1920f / 2.4f, fitted.height, 0.5f)
        assertTrue(fitted.height < viewport.height)
    }

    @Test
    fun `gutter is left of center when left page is narrower`() {
        val viewport = Size(1920f, 1080f)
        val gutter = spreadGutterX(leftAspect = 0.5f, rightAspect = 0.9f, viewport = viewport)
        val fitted = fitSpreadSize(1.4f, viewport)
        val origin = (viewport.width - fitted.width) / 2f
        assertEquals(origin + fitted.width * (0.5f / 1.4f), gutter, 0.5f)
        assertTrue(gutter < viewport.width / 2f)
    }
}
