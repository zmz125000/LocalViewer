package com.hippo.ehviewer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaHeapOomTest {
    @Test
    fun detectsArtFailedToAllocateMessage() {
        val e = RuntimeException(
            "Failed to allocate a 33554448 byte allocation with 19524544 free bytes " +
                "and 18MB until OOM, target footprint 536870912, growth limit 536870912",
        )
        assertTrue(e.isJavaHeapOom())
    }

    @Test
    fun detectsWrappedOutOfMemoryError() {
        val e = RuntimeException("coil decode", OutOfMemoryError("Failed to allocate"))
        assertTrue(e.isJavaHeapOom())
    }

    @Test
    fun ignoresUnrelatedFailures() {
        assertFalse(IllegalStateException("Not cached").isJavaHeapOom())
    }
}
