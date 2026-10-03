package com.hippo.ehviewer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekScrubTest {
    @Test
    fun fullWidthDragMovesOneMinute() {
        val width = 1000
        val movie = 2L * 60L * 60L * 1000L
        assertEquals(SCRUB_WINDOW_MS, scrubSeekDeltaMs(width.toFloat(), width, movie))
        assertEquals(-SCRUB_WINDOW_MS, scrubSeekDeltaMs(-width.toFloat(), width, movie))
        assertEquals(60_000L, SCRUB_WINDOW_MS)
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
}
