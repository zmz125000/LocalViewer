package com.hippo.ehviewer.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginCacheFreshTest {
    @Test
    fun unknownOrZeroRemoteKeepsCache() {
        assertFalse(OriginCacheFresh.remoteNewerThanCache(null, 100L))
        assertFalse(OriginCacheFresh.remoteNewerThanCache(0L, 100L))
        assertFalse(OriginCacheFresh.remoteNewerThanCache(-1L, 100L))
        assertFalse(OriginCacheFresh.sizeMismatch(null, 100L))
        assertFalse(
            OriginCacheFresh.isStale(
                remoteSize = null,
                remoteMtimeMs = null,
                cacheSize = 100L,
                cacheMtimeMs = 50L,
            ),
        )
    }

    @Test
    fun remoteLaterThanCacheIsStale() {
        assertTrue(OriginCacheFresh.remoteNewerThanCache(200L, 100L))
        assertFalse(OriginCacheFresh.remoteNewerThanCache(100L, 100L))
        assertFalse(OriginCacheFresh.remoteNewerThanCache(50L, 100L))
    }

    @Test
    fun sizeMismatchIsStaleEvenWhenMtimeIsOlder() {
        assertTrue(OriginCacheFresh.sizeMismatch(200L, 100L))
        assertFalse(OriginCacheFresh.sizeMismatch(100L, 100L))
        assertTrue(
            OriginCacheFresh.isStale(
                remoteSize = 50L,
                remoteMtimeMs = 10L,
                cacheSize = 100L,
                cacheMtimeMs = 20L,
            ),
        )
        assertFalse(
            OriginCacheFresh.isStale(
                remoteSize = 100L,
                remoteMtimeMs = 10L,
                cacheSize = 100L,
                cacheMtimeMs = 20L,
            ),
        )
    }

    @Test
    fun zipMemberSkipsSizeAndUsesZipMtime() {
        assertFalse(
            OriginCacheFresh.isStale(
                remoteSize = 9_999L,
                remoteMtimeMs = 10L,
                cacheSize = 12L,
                cacheMtimeMs = 20L,
                compareSize = false,
            ),
        )
        assertTrue(
            OriginCacheFresh.isStale(
                remoteSize = 9_999L,
                remoteMtimeMs = 30L,
                cacheSize = 12L,
                cacheMtimeMs = 20L,
                compareSize = false,
            ),
        )
    }
}
