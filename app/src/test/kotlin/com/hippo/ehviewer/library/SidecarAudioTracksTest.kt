package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarAudioTracksTest {
    @Test
    fun matchesNamedAudioTracksWithoutIncludingOtherEpisodes() {
        val names = listOf(
            "Episode 02.mka",
            "Episode 02.ja.AC3",
            "Episode 02 commentary.flac",
            "Episode 03.mka",
            "Episode 02.srt",
        )

        assertEquals(
            listOf("Episode 02 commentary.flac", "Episode 02.ja.AC3", "Episode 02.mka"),
            SidecarAudioTracks.matchSiblings("Episode 02.mkv", names),
        )
    }

    @Test
    fun recognizesExternalAudioFormats() {
        assertTrue(SidecarAudioTracks.isAudioFileName("movie.MKA"))
        assertTrue(SidecarAudioTracks.isAudioFileName("movie.eac3"))
        assertFalse(SidecarAudioTracks.isAudioFileName("movie.mkv"))
        assertFalse(SidecarAudioTracks.isAudioFileName(".hidden.mka"))
        assertEquals("audio/x-matroska", mimeTypeForFileName("movie.mka"))
        assertEquals("audio/ac3", mimeTypeForFileName("movie.ac3"))
    }
}
