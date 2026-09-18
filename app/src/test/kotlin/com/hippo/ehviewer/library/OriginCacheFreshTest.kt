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
    }

    @Test
    fun remoteLaterThanCacheIsStale() {
        assertTrue(OriginCacheFresh.remoteNewerThanCache(200L, 100L))
        assertFalse(OriginCacheFresh.remoteNewerThanCache(100L, 100L))
        assertFalse(OriginCacheFresh.remoteNewerThanCache(50L, 100L))
    }
}
