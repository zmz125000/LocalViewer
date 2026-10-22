package com.hippo.ehviewer.library

import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseChildMetadataTest {
    @Test
    fun safFillsGapsInIndexedFileMetadata() {
        val indexed = BrowseChild("a.jpg", false, "/album/a.jpg".toPath(), fromMediaStore = true)
        val saf = indexed.copy(size = 1234L, lastModifiedMs = 2000L, fromMediaStore = false)
        val merged = mergeDuplicateBrowseChildren(listOf(indexed, saf)).single()
        assertEquals(1234L, merged.size)
        assertEquals(2000L, merged.lastModifiedMs)
    }

    @Test
    fun safMissingValuesDoNotEraseIndexedMetadata() {
        val indexed = BrowseChild(
            "a.jpg",
            false,
            "/album/a.jpg".toPath(),
            size = 1234L,
            lastModifiedMs = 2000L,
            fromMediaStore = true,
        )
        val saf = indexed.copy(size = 0L, lastModifiedMs = 0L, fromMediaStore = false)
        val merged = mergeDuplicateBrowseChildren(listOf(indexed, saf)).single()
        assertEquals(1234L, merged.size)
        assertEquals(2000L, merged.lastModifiedMs)
    }
}
