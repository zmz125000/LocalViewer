package com.hippo.ehviewer.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpBodyRetryTest {
    @Test
    fun midRangeFailureRetries() {
        assertTrue(httpBodyShouldRetryRead(-1, remaining = 1024L))
    }

    @Test
    fun playheadEofDoesNotReconnectSharedBody() {
        assertFalse(httpBodyShouldRetryRead(0, remaining = 1L))
        assertFalse(httpBodyShouldRetryRead(0, remaining = 1024L))
    }

    @Test
    fun finishedRangeDoesNotRetry() {
        assertFalse(httpBodyShouldRetryRead(0, remaining = 0L))
        assertFalse(httpBodyShouldRetryRead(-1, remaining = 0L))
        assertFalse(httpBodyShouldRetryRead(64, remaining = 1024L))
    }

    @Test
    fun headerProbeAndStartPlaybackAreNotSeeks() {
        val resumeAt = 50L * 1024 * 1024
        assertFalse(httpRangeIsPlaybackSeek(previousPlayhead = Long.MIN_VALUE, start = 0L))
        assertFalse(httpRangeIsPlaybackSeek(previousPlayhead = resumeAt, start = resumeAt))
    }

    @Test
    fun resumeAndPlayheadJumpAreSeeks() {
        val resumeAt = 50L * 1024 * 1024
        assertTrue(httpRangeIsPlaybackSeek(previousPlayhead = Long.MIN_VALUE, start = resumeAt))
        assertTrue(httpRangeIsPlaybackSeek(previousPlayhead = 0L, start = resumeAt))
    }
}
