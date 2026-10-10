package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ScalerCacheTileTest {
    @Test
    fun `screen page is one tile`() {
        assertEquals(listOf(ScalerTile(0, 0, 1080, 2400)), scalerCacheTiles(1080, 2400))
    }

    @Test
    fun `long strip splits on the max edge without gaps or overlap`() {
        val tiles = scalerCacheTiles(1280, 20_000, maxEdge = 8192)
        assertEquals(3, tiles.size)
        assertEquals(ScalerTile(0, 0, 1280, 8192), tiles[0])
        assertEquals(ScalerTile(0, 8192, 1280, 8192), tiles[1])
        assertEquals(ScalerTile(0, 16384, 1280, 3616), tiles[2])
        assertEquals(20_000, tiles.sumOf { it.height })
    }

    @Test
    fun `wide and tall page is a grid`() {
        val tiles = scalerCacheTiles(10_000, 9_000, maxEdge = 8192)
        assertEquals(
            listOf(
                ScalerTile(0, 0, 8192, 8192),
                ScalerTile(8192, 0, 1808, 8192),
                ScalerTile(0, 8192, 8192, 808),
                ScalerTile(8192, 8192, 1808, 808),
            ),
            tiles,
        )
    }

    @Test
    fun `zoom at rest keeps the layout size`() {
        assertEquals(1080 to 2400, scalerCachePixelSize(1080, 2400, zoom = 1f))
        assertEquals(1080 to 2400, scalerCachePixelSize(1080, 2400, zoom = 0.5f))
    }

    @Test
    fun `settled zoom bakes extra pixels up to the layer edge`() {
        assertEquals(2160 to 4800, scalerCachePixelSize(1080, 2400, zoom = 2f))
        assertEquals(3686 to 8192, scalerCachePixelSize(1080, 2400, zoom = 8f))
    }

    @Test
    fun `large photo uses the downscale kernel until the source pixels`() {
        val fit = scalerDrawPlan(1000, 1500, 4000, 6000, zoom = 1f, upMode = 6, downMode = 4, limitUpscale = true)
        assertEquals(ScalerDraw(4, 1000, 1500), fit)
        val closer = scalerDrawPlan(1000, 1500, 4000, 6000, zoom = 2f, upMode = 6, downMode = 4, limitUpscale = true)
        assertEquals(ScalerDraw(4, 2000, 3000), closer)
        val pastNative = scalerDrawPlan(1000, 1500, 4000, 6000, zoom = 5f, upMode = 6, downMode = 4, limitUpscale = true)
        assertEquals(0, pastNative.mode)
    }

    @Test
    fun `upscale limit keeps pinch enlarge on the fitted kernel`() {
        val fit = scalerDrawPlan(1000, 1500, 500, 750, zoom = 1f, upMode = 6, downMode = 4, limitUpscale = true)
        assertEquals(ScalerDraw(6, 1000, 1500), fit)
        val pinched = scalerDrawPlan(1000, 1500, 500, 750, zoom = 3f, upMode = 6, downMode = 4, limitUpscale = true)
        assertEquals(ScalerDraw(6, 1000, 1500), pinched)
    }

    @Test
    fun `upscale limit off bakes the enlarge kernel at the zoom`() {
        val pinched = scalerDrawPlan(1000, 1500, 500, 750, zoom = 3f, upMode = 6, downMode = 4, limitUpscale = false)
        assertEquals(ScalerDraw(6, 3000, 4500), pinched)
    }

    @Test
    fun `one to one fit stays on the gpu blit`() {
        val fit = scalerDrawPlan(1000, 1500, 1000, 1500, zoom = 1f, upMode = 6, downMode = 4, limitUpscale = true)
        assertEquals(0, fit.mode)
    }

    @Test
    fun `empty size has no tiles`() {
        assertEquals(emptyList<ScalerTile>(), scalerCacheTiles(0, 100))
        assertEquals(emptyList<ScalerTile>(), scalerCacheTiles(100, 0))
    }
}
