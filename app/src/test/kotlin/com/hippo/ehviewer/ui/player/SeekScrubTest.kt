package com.hippo.ehviewer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekScrubTest {
    @Test
    fun fullWidthDragDependsOnOrientation() {
        val width = 1000
        val movie = 20 * 60_000L
        assertEquals(60_000L, SCRUB_WINDOW_PORTRAIT_MS)
        assertEquals(120_000L, SCRUB_WINDOW_LANDSCAPE_MS)
        assertEquals(SCRUB_WINDOW_PORTRAIT_MS, scrubWindowMs(viewWidthPx = 1080, viewHeightPx = 1920))
        assertEquals(SCRUB_WINDOW_LANDSCAPE_MS, scrubWindowMs(viewWidthPx = 1920, viewHeightPx = 1080))
        assertEquals(
            SCRUB_WINDOW_PORTRAIT_MS,
            scrubSeekDeltaMs(width.toFloat(), width, movie, SCRUB_WINDOW_PORTRAIT_MS),
        )
        assertEquals(
            -SCRUB_WINDOW_LANDSCAPE_MS,
            scrubSeekDeltaMs(-width.toFloat(), width, movie, SCRUB_WINDOW_LANDSCAPE_MS),
        )
    }

    @Test
    fun videosLongerThanTwentyMinutesDoubleTheSeekDistance() {
        val movie = 20 * 60_000L + 1
        assertEquals(120_000L, scrubSeekDeltaMs(1000f, 1000, movie, SCRUB_WINDOW_PORTRAIT_MS))
        assertEquals(-240_000L, scrubSeekDeltaMs(-1000f, 1000, movie, SCRUB_WINDOW_LANDSCAPE_MS))
    }

    @Test
    fun shortVideoUsesItsOwnDuration() {
        assertEquals(15_000L, scrubSeekDeltaMs(500f, 1000, durationMs = 30_000L))
    }

    @Test
    fun unknownDurationOrWidthDoesNotSeek() {
        assertEquals(0L, scrubSeekDeltaMs(400f, 1000, durationMs = -1L))
        assertEquals(0L, scrubSeekDeltaMs(400f, 0, durationMs = 60_000L))
    }

    @Test
    fun verticalGesturesReserveTheMiddleHalfForThePlaylist() {
        assertEquals(VideoLevelControl.VOLUME, videoLevelControlAt(249f, 1000))
        assertEquals(null, videoLevelControlAt(250f, 1000))
        assertEquals(null, videoLevelControlAt(749f, 1000))
        assertEquals(VideoLevelControl.BRIGHTNESS, videoLevelControlAt(750f, 1000))
    }

    @Test
    fun verticalLevelDragUsesSliderStepsAndClampsToItsRange() {
        assertEquals(7, videoDragLevel(7, -4f, 1000, 0..15))
        assertEquals(10, videoDragLevel(7, -100f, 1000, 0..15))
        assertEquals(0, videoDragLevel(7, 600f, 1000, 0..15))
        assertEquals(1, videoDragLevel(50, 300f, 600, 1..100))
        assertEquals(100, videoDragLevel(50, -300f, 600, 1..100))
    }
}
