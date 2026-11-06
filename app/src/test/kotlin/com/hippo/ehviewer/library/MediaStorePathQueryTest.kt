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
            arrayOf("Pictures/Comics/", "Pictures/Comics", "Pictures/Comics/%", "external_primary"),
            args,
        )
    }

    @Test
    fun `removable paths scope listing and opening to their volume`() {
        val path = "@volume-abcd-1234/Pictures/Comics"
        val descendants = MediaStorePathQuery.descendantRelativePathSelection(path)!!
        assertTrue(descendants.first.contains("volume_name = ?"))
        assertArrayEquals(arrayOf("Pictures/Comics/", "Pictures/Comics", "Pictures/Comics/%", "abcd-1234"), descendants.second)
        assertArrayEquals(arrayOf("abcd-1234"), MediaStorePathQuery.descendantRelativePathSelection("@volume-abcd-1234")!!.second)
        assertArrayEquals(arrayOf("Pictures/Comics/", "Pictures/Comics", "001.jpg", "abcd-1234"), MediaStorePathQuery.resolveByRelativePathSelection(path, "001.jpg").second)
        assertArrayEquals(arrayOf("%/Pictures/Comics/001.jpg", "001.jpg", "abcd-1234"), MediaStorePathQuery.resolveByDataSelection(path, "001.jpg").second)
    }

    @Test
    fun `resolve prefers RELATIVE_PATH then DATA fallback`() {
        val byRel = MediaStorePathQuery.resolveByRelativePathSelection("Movies", "clip.mp4")
        assertFalse(byRel.first.contains(MediaStorePathQuery.DATA))
        assertArrayEquals(arrayOf("Movies/", "Movies", "clip.mp4", "external_primary"), byRel.second)

        val byData = MediaStorePathQuery.resolveByDataSelection("Movies", "clip.mp4")
        assertTrue(byData.first.contains(MediaStorePathQuery.DATA))
        assertEquals("%/Movies/clip.mp4", byData.second[0])
        assertEquals("clip.mp4", byData.second[1])
    }
}
