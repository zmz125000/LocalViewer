package com.hippo.ehviewer.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbDiskFormatTest {
    @Test
    fun leftoverJpegIsReusedUntilWebpExists() {
        val dir = File(System.getProperty("java.io.tmpdir"), "thumb-disk-${System.nanoTime()}").apply { mkdirs() }
        try {
            val webp = File(dir, "abc.webp")
            val jpg = File(dir, "abc.jpg")
            assertNull(OriginDiskCache.existingThumb(webp))
            jpg.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(jpg, OriginDiskCache.existingThumb(webp))
            webp.writeBytes(byteArrayOf(4, 5))
            assertEquals(webp, OriginDiskCache.existingThumb(webp))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun jpegSiblingKeepsSameHashStem() {
        val webp = File("/cache/deadbeef.webp")
        val jpg = OriginDiskCache.jpegSibling(webp)
        assertEquals("deadbeef.jpg", jpg.name)
        assertTrue(OriginDiskCache.thumbFileName("deadbeef").endsWith(".webp"))
    }
}
