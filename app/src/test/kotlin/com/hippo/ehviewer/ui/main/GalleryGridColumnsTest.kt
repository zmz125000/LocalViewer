package com.hippo.ehviewer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryGridColumnsTest {
    @Test
    fun phoneWidthKeepsThreeColumnsAtDefaultSize() {
        // 360dp window, 12dp margin each side, 12dp gutter, 100dp target. No rail.
        assertEquals(3, adaptiveColumnCount(availablePx = 336, minCellPx = 100, gutterPx = 12))
    }

    @Test
    fun unfoldedWidthAddsColumnsAndStillFillsTheRow() {
        // 840dp window, 80dp rail, 20dp margin each side, 16dp gutter, same 100dp target.
        assertEquals(6, adaptiveColumnCount(availablePx = 720, minCellPx = 100, gutterPx = 16))
    }

    @Test
    fun narrowWindowDropsColumnsInsteadOfOverflowing() {
        assertEquals(1, adaptiveColumnCount(availablePx = 90, minCellPx = 100, gutterPx = 12))
        assertEquals(2, adaptiveColumnCount(availablePx = 220, minCellPx = 100, gutterPx = 12))
    }

    @Test
    fun largerTargetFitsFewerColumnsInTheSameWidth() {
        assertEquals(3, adaptiveColumnCount(availablePx = 336, minCellPx = 100, gutterPx = 12))
        assertEquals(2, adaptiveColumnCount(availablePx = 336, minCellPx = 160, gutterPx = 12))
    }
}
