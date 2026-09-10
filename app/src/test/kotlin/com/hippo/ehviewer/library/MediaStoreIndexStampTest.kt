package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreIndexStampTest {
    @Test
    fun `encode round-trips`() {
        val stamp = MediaStoreIndexStamp(
            generation = 42L,
            count = 1200,
            maxDateModifiedSecs = 1_700_000_000L,
            idXor = 0xABCDL,
        )
        val parsed = MediaStoreIndexStamp.parse(stamp.encode(7L))
        assertEquals(7L, parsed!!.first)
        assertEquals(stamp, parsed.second)
    }

    @Test
    fun `parse rejects malformed`() {
        assertNull(MediaStoreIndexStamp.parse("7:1:2"))
        assertNull(MediaStoreIndexStamp.parse("x:1:2:3:4"))
    }

    @Test
    fun `generation match skips without needing fingerprint equality`() {
        val stored = MediaStoreIndexStamp(10L, count = 1, maxDateModifiedSecs = 1L, idXor = 1L)
        val current = MediaStoreIndexStamp(10L, count = 99, maxDateModifiedSecs = 9L, idXor = 9L)
        assertTrue(stored.generationUnchanged(10L))
        assertTrue(MediaStoreIndexStamp.shouldSkip(stored, current, hasGalleries = true))
    }

    @Test
    fun `fingerprint match skips when generation moved`() {
        val stored = MediaStoreIndexStamp(10L, count = 5, maxDateModifiedSecs = 100L, idXor = 7L)
        val current = MediaStoreIndexStamp(11L, count = 5, maxDateModifiedSecs = 100L, idXor = 7L)
        assertFalse(stored.generationUnchanged(11L))
        assertTrue(stored.sameImages(current))
        assertTrue(MediaStoreIndexStamp.shouldSkip(stored, current, hasGalleries = true))
    }

    @Test
    fun `zero generation is not a skip signal`() {
        val stored = MediaStoreIndexStamp(0L, count = 1, maxDateModifiedSecs = 1L, idXor = 1L)
        val current = MediaStoreIndexStamp(0L, count = 99, maxDateModifiedSecs = 9L, idXor = 9L)
        assertFalse(stored.generationUnchanged(0L))
        assertFalse(MediaStoreIndexStamp.shouldSkip(stored, current, hasGalleries = true))
    }

    @Test
    fun `empty library or missing stamp never skips`() {
        val current = MediaStoreIndexStamp(1L, 1, 1L, 1L)
        assertFalse(MediaStoreIndexStamp.shouldSkip(null, current, hasGalleries = true))
        assertFalse(
            MediaStoreIndexStamp.shouldSkip(
                MediaStoreIndexStamp(1L, 1, 1L, 1L),
                current,
                hasGalleries = false,
            ),
        )
    }

    @Test
    fun `unknown generation falls through to fingerprint`() {
        val stored = MediaStoreIndexStamp(
            MediaStoreIndexStamp.GENERATION_UNKNOWN,
            count = 3,
            maxDateModifiedSecs = 8L,
            idXor = 2L,
        )
        val same = stored.copy(generation = MediaStoreIndexStamp.GENERATION_UNKNOWN)
        val different = stored.copy(count = 4)
        assertFalse(stored.generationUnchanged(MediaStoreIndexStamp.GENERATION_UNKNOWN))
        assertTrue(MediaStoreIndexStamp.shouldSkip(stored, same, hasGalleries = true))
        assertFalse(MediaStoreIndexStamp.shouldSkip(stored, different, hasGalleries = true))
    }
}
