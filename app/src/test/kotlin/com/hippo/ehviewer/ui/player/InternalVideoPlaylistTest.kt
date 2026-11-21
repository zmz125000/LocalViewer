package com.hippo.ehviewer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalVideoPlaylistTest {
    @Test
    fun discoveryKeepsTheCurrentVideoInNaturalOrder() {
        val current = InternalVideoSource.Smb(1, "Videos/Episode 2.mkv")
        val first = current.copy(remotePath = "Videos/Episode 1.mkv")
        val last = current.copy(remotePath = "Videos/Episode 10.mkv")
        val initial = InternalVideoPlaylistRegistry.create(current, emptyList())
        try {
            assertTrue(initial.session.discoverSiblings)
            assertEquals(listOf(current), initial.session.items)
            val result = checkNotNull(
                InternalVideoPlaylistRegistry.completeDiscovery(initial.session, current, listOf(last, current, first)),
            )
            assertFalse(result.session.discoverSiblings)
            assertEquals(listOf(first, current, last), result.session.items)
            assertEquals(1, result.initialIndex)
        } finally {
            InternalVideoPlaylistRegistry.remove(initial.session.id)
        }
    }

    @Test
    fun discoveryCannotRestoreAClosedPlaylist() {
        val current = InternalVideoSource.Local("/Movies/a.mp4")
        val initial = InternalVideoPlaylistRegistry.create(current, emptyList())
        InternalVideoPlaylistRegistry.remove(initial.session.id)
        assertNull(InternalVideoPlaylistRegistry.completeDiscovery(initial.session, current, emptyList()))
    }
}
