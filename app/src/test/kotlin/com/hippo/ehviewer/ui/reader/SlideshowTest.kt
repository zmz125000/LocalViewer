package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlideshowTest {
    @Test
    fun orientationFitUsesViewportOrientation() {
        val image = Size(600f, 800f)
        assertEquals(ContentScale.FillWidth, ContentScale.fromPreferences(7, image, Size(800f, 1200f)))
        assertEquals(ContentScale.FillHeight, ContentScale.fromPreferences(7, image, Size(1200f, 800f)))
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
