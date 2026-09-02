package com.hippo.ehviewer.gallery

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ArchivePageLoaderTest {
    @Test
    fun emptyNamesKeepsNativeOrder() {
        val names = listOf("a.jpg", "b.jpg")
        assertArrayEquals(
            intArrayOf(0, 1),
            nativeIndicesForZipFolder("", emptyList(), names),
        )
    }

    @Test
    fun flatZipMatchesRootBasenames() {
        val native = listOf("002.jpg", "001.jpg")
        val images = listOf("001.jpg", "002.jpg")
        assertArrayEquals(
            intArrayOf(0, 1),
            nativeIndicesForZipFolder("", images, native),
        )
    }

    @Test
    fun wrapperFolderMatchesExactPrefix() {
        val native = listOf("Album/002.jpg", "Album/001.jpg", "other/001.jpg")
        val images = listOf("001.jpg", "002.jpg")
        assertArrayEquals(
            intArrayOf(0, 1),
            nativeIndicesForZipFolder("Album", images, native),
        )
    }

    @Test
    fun sameDepthBasenameWhenFolderEncodingDiffers() {
        val native = listOf("??/001.jpg", "??/002.jpg")
        val images = listOf("001.jpg", "002.jpg")
        assertArrayEquals(
            intArrayOf(0, 1),
            nativeIndicesForZipFolder("园区", images, native),
        )
    }

    @Test
    fun nestedInnerPrefix() {
        val native = listOf("a/b/1.jpg", "a/c/1.jpg")
        assertArrayEquals(
            intArrayOf(1),
            nativeIndicesForZipFolder("a/c", listOf("1.jpg"), native),
        )
    }
}
