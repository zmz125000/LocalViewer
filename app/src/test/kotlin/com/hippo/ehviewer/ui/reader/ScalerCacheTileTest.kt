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
    fun `empty size has no tiles`() {
        assertEquals(emptyList<ScalerTile>(), scalerCacheTiles(0, 100))
        assertEquals(emptyList<ScalerTile>(), scalerCacheTiles(100, 0))
    }
}
