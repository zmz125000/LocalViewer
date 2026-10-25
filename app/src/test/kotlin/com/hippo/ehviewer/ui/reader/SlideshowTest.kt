package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTest {
    @Test
    fun fillScreenCoversViewportWithoutDistortingImageInEitherOrientation() {
        for (image in listOf(Size(600f, 800f), Size(2400f, 600f))) {
            for (viewport in listOf(Size(800f, 1200f), Size(1200f, 800f))) {
                val factor = ContentScale.fromPreferences(7, image, viewport).computeScaleFactor(image, viewport)
                assertEquals(factor.scaleX, factor.scaleY, 0.0001f)
                val width = image.width * factor.scaleX
                val height = image.height * factor.scaleY
                assertTrue(width >= viewport.width - 0.001f)
                assertTrue(height >= viewport.height - 0.001f)
                assertTrue(width > viewport.width || height > viewport.height)
            }
        }
    }

    @Test
    fun pdfSlideshowFallsBackToDefaultWithoutChangingOtherModes() {
        assertEquals(ReadingModeType.fromPdfPreference(0), ReadingModeType.fromPdfPreference(6))
        assertEquals(ReadingModeType.RIGHT_TO_LEFT, ReadingModeType.fromPdfPreference(6))
        for (mode in ReadingModeType.entries.filter { it != ReadingModeType.DEFAULT && it != ReadingModeType.SLIDESHOW }) {
            assertEquals(mode, ReadingModeType.fromPdfPreference(mode.prefValue))
        }
    }

    @Test
    fun slideshowUsesHorizontalPager() {
        assertEquals(ReadingModeType.SLIDESHOW, ReadingModeType.fromPreference(6))
        assertFalse(ReadingModeType.isWebtoon(ReadingModeType.SLIDESHOW))
        assertFalse(ReadingModeType.isVertical(ReadingModeType.SLIDESHOW))
    }
}
