package com.hippo.ehviewer.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePathQueryTest {
    @Test
    fun `library scan filters paths without a redundant volume predicate`() {
        val (selection, args) = MediaStorePathQuery.libraryDescendantSelection("Pictures/Comics")!!
        assertFalse(selection.contains("volume_name"))
        assertArrayEquals(arrayOf("Pictures/Comics/", "Pictures/Comics", "Pictures/Comics/%"), args)
        assertNull(MediaStorePathQuery.libraryDescendantSelection("@volume-abcd-1234"))
    }

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
    fun `reader resolves reported OTG file on its physical volume despite matching internal file`() {
        val queries = mutableListOf<String>()
        fun resolve(path: String): String? = MediaStorePathQuery.resolveFile(path) { volume, selection, args ->
            queries += volume
            assertTrue(selection.contains("volume_name = ?"))
            assertArrayEquals(arrayOf("1-06/", "1-06", "000.jpg", volume), args)
            when (volume) {
                "1c1e-29b5" -> "content://media/1c1e-29b5/images/media/42"
                "external_primary" -> "content://media/external_primary/images/media/99"
                else -> null
            }
        }
        assertEquals("content://media/1c1e-29b5/images/media/42", resolve("mediastore:/@volume-1c1e-29b5/1-06/000.jpg"))
        assertEquals(listOf("1c1e-29b5"), queries)
        assertEquals("content://media/external_primary/images/media/99", resolve("mediastore:/1-06/000.jpg"))
    }

    @Test
    fun `reader OEM fallback retains volume and real relative directory`() {
        var calls = 0
        val result = MediaStorePathQuery.resolveFile("mediastore:/@volume-1c1e-29b5/1-06/000.jpg") { volume, selection, args ->
            calls++
            assertEquals("1c1e-29b5", volume)
            if (selection.contains(MediaStorePathQuery.DATA)) {
                assertArrayEquals(arrayOf("%/1-06/000.jpg", "000.jpg", volume), args)
                "content://media/1c1e-29b5/images/media/42"
            } else {
                null
            }
        }
        assertEquals(2, calls)
        assertEquals("content://media/1c1e-29b5/images/media/42", result)
    }

    @Test
    fun `reader does not query directories or non MediaStore paths as files`() {
        for (path in listOf("mediastore:/", "mediastore:/@volume-1c1e-29b5", "mediastore:/@volume-1c1e-29b5/", "/Pictures/000.jpg")) {
            val result = MediaStorePathQuery.resolveFile<String>(path) { _, _, _ ->
                throw AssertionError("Unexpected file lookup for $path")
            }
            assertNull(result)
        }
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
