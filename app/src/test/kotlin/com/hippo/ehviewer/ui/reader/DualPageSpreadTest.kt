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
    fun `unscaled pair is height-aligned`() {
        val size = unscaledSpreadSize(
            left = Size(1400f, 2000f),
            right = Size(1400f, 2000f),
            leftAspect = 0.7f,
            rightAspect = 0.7f,
        )
        assertEquals(2800f, size.width, 0.5f)
        assertEquals(2000f, size.height, 0.01f)
    }

    @Test
    fun `unscaled solo is the one decoded page`() {
        val size = unscaledSpreadSize(
            left = Size(1400f, 2000f),
            right = null,
            leftAspect = 0.7f,
            rightAspect = 0f,
        )
        assertEquals(1400f, size.width, 0.5f)
        assertEquals(2000f, size.height, 0.01f)
    }

    @Test
    fun `unscaled waits for a decoded height`() {
        assertEquals(Size.Zero, unscaledSpreadSize(null, null, 0.7f, 0.7f))
    }

    @Test
    fun `small square pair stays smaller than viewport-fitted spread`() {
        val viewport = Size(2400f, 1080f)
        val unscaled = unscaledSpreadSize(
            left = Size(1000f, 1000f),
            right = Size(1000f, 1000f),
            leftAspect = 1f,
            rightAspect = 1f,
        )
        val fitted = fitSpreadSize(2f, viewport)
        assertEquals(2000f, unscaled.width, 0.01f)
        assertEquals(1000f, unscaled.height, 0.01f)
        assertEquals(2160f, fitted.width, 0.5f)
        assertEquals(1080f, fitted.height, 0.01f)
        assertTrue(unscaled.width < fitted.width)
        assertTrue(unscaled.height < fitted.height)
        val inside = insideSpreadSize(unscaled, viewport)
        assertEquals(unscaled.width, inside.width, 0.01f)
        assertEquals(unscaled.height, inside.height, 0.01f)
    }

    @Test
    fun `large pair inside size matches viewport fit`() {
        val viewport = Size(2400f, 1080f)
        val unscaled = unscaledSpreadSize(
            left = Size(1400f, 2000f),
            right = Size(1400f, 2000f),
            leftAspect = 0.7f,
            rightAspect = 0.7f,
        )
        val inside = insideSpreadSize(unscaled, viewport)
        val fitted = fitSpreadSize(1.4f, viewport)
        assertEquals(fitted.width, inside.width, 0.5f)
        assertEquals(fitted.height, inside.height, 0.5f)
        assertTrue(inside.width <= viewport.width)
        assertTrue(inside.height <= viewport.height)
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
