package com.hippo.ehviewer.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePathQueryTest {
    @Test
    fun `device root has no SQL filter`() {
        assertNull(MediaStorePathQuery.descendantRelativePathSelection(""))
        assertNull(MediaStorePathQuery.descendantRelativePathSelection("/"))
    }

    @Test
    fun `nested root uses RELATIVE_PATH prefix without DATA LIKE`() {
        val (selection, args) = MediaStorePathQuery.descendantRelativePathSelection("Pictures/Comics")!!
        assertFalse(selection.contains(MediaStorePathQuery.DATA))
        assertFalse(args.any { it.startsWith("%/") })
        assertTrue(selection.contains(MediaStorePathQuery.RELATIVE_PATH))
        assertArrayEquals(
            arrayOf("Pictures/Comics/", "Pictures/Comics", "Pictures/Comics/%"),
            args,
        )
    }

    @Test
    fun `resolve prefers RELATIVE_PATH then DATA fallback`() {
        val byRel = MediaStorePathQuery.resolveByRelativePathSelection("Movies", "clip.mp4")
        assertFalse(byRel.first.contains(MediaStorePathQuery.DATA))
        assertArrayEquals(arrayOf("Movies/", "Movies", "clip.mp4"), byRel.second)

        val byData = MediaStorePathQuery.resolveByDataSelection("Movies", "clip.mp4")
        assertTrue(byData.first.contains(MediaStorePathQuery.DATA))
        assertEquals("%/Movies/clip.mp4", byData.second[0])
        assertEquals("clip.mp4", byData.second[1])
    }
}
