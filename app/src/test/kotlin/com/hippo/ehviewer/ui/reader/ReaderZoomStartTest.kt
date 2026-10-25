package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomStartTest {
    @Test
    fun changingStartPositionRepositionsAlreadyZoomedContent() {
        val bounds = Rect(-300f, 0f, 1700f, 800f)
        val viewport = Size(1000f, 800f)
        assertEquals(Offset(300f, 0f), readerZoomStartPan(bounds, viewport, AbsoluteAlignment.Left))
        assertEquals(Offset(-700f, 0f), readerZoomStartPan(bounds, viewport, AbsoluteAlignment.Right))
        assertEquals(Offset(-200f, 0f), readerZoomStartPan(bounds, viewport, Alignment.CenterHorizontally))
    }

    @Test
    fun imagesThatFitRemainCentered() {
        assertEquals(
            Offset(200f, 200f),
            readerZoomStartPan(Rect(0f, 0f, 600f, 400f), Size(1000f, 800f), AbsoluteAlignment.Left),
        )
    }

    @Test
    fun tallPagesStartAtTopAndViewportChangesReapplyHorizontalStart() {
        assertEquals(
            Offset(0f, 500f),
            readerZoomStartPan(Rect(0f, -500f, 1000f, 2500f), Size(1000f, 800f), AbsoluteAlignment.Right),
        )
        val bounds = Rect(-800f, 0f, 1200f, 1000f)
        assertEquals(Offset.Zero, readerZoomStartPan(bounds, Size(1200f, 800f), AbsoluteAlignment.Right))
        assertEquals(Offset(400f, 0f), readerZoomStartPan(bounds, Size(1600f, 800f), AbsoluteAlignment.Right))
    }

    @Test
    fun automaticStartRespectsReadingDirectionAndExplicitChoicesRemainAbsolute() {
        assertEquals(AbsoluteAlignment.Right, Alignment.fromPreferences(1, isRtl = true, isVertical = false))
        assertEquals(AbsoluteAlignment.Left, Alignment.fromPreferences(1, isRtl = false, isVertical = false))
        assertEquals(Alignment.CenterHorizontally, Alignment.fromPreferences(1, isRtl = true, isVertical = true))
        assertEquals(AbsoluteAlignment.Left, Alignment.fromPreferences(2, isRtl = true, isVertical = false))
        assertEquals(AbsoluteAlignment.Right, Alignment.fromPreferences(3, isRtl = false, isVertical = false))
    }
}
