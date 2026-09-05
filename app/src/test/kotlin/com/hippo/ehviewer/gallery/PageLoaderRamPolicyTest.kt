package com.hippo.ehviewer.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoaderRamPolicyTest {
    @Test
    fun gifAndAnimatedWebpAreHeavy() {
        assertTrue(isAnimatedReaderExtension("gif"))
        assertTrue(isAnimatedReaderExtension("GIF"))
        assertTrue(isAnimatedReaderExtension(".webp"))
        assertTrue(isAnimatedReaderExtension("awebp"))
        assertTrue(isAnimatedReaderExtension("apng"))
        assertFalse(isAnimatedReaderExtension("jpg"))
        assertFalse(isAnimatedReaderExtension("jpeg"))
        assertFalse(isAnimatedReaderExtension("jxl"))
        assertFalse(isAnimatedReaderExtension(null))
    }
}
