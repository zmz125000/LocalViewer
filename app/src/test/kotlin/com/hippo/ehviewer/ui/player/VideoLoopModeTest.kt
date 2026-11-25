package com.hippo.ehviewer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLoopModeTest {
    @Test
    fun playlistWrapsInBothDirections() {
        assertEquals(0, VideoLoopMode.PLAYLIST.adjacentIndex(2, 3, 1))
        assertEquals(2, VideoLoopMode.PLAYLIST.adjacentIndex(0, 3, -1))
    }

    @Test
    fun otherModesKeepPlaylistBoundaries() {
        for (mode in VideoLoopMode.entries.filter { it != VideoLoopMode.PLAYLIST }) {
            assertEquals(2, mode.adjacentIndex(1, 3, 1))
            assertNull(mode.adjacentIndex(2, 3, 1))
            assertNull(mode.adjacentIndex(0, 3, -1))
        }
    }

    @Test
    fun playlistsWithOneOrNoItemsHaveNoAdjacentItem() {
        assertNull(VideoLoopMode.PLAYLIST.adjacentIndex(0, 1, 1))
        assertNull(VideoLoopMode.PLAYLIST.adjacentIndex(0, 0, -1))
    }
}
