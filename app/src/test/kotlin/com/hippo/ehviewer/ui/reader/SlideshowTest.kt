package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowTest {
    @Test
    fun shuffleVisitsEveryOtherPageOnceThenEnds() {
        val shuffle = SlideshowShuffle()
        val visited = mutableListOf(2)
        repeat(9) {
            visited += requireNotNull(shuffle.next(visited.last(), 10))
        }
        assertEquals((0 until 10).toSet(), visited.toSet())
        assertNull(shuffle.next(visited.last(), 10))
        shuffle.reset(visited.last(), 10)
        assertFalse(shuffle.next(visited.last(), 10) == visited.last())
    }

    @Test
    fun shuffleHandlesSinglePageAndChangingPageCount() {
        val shuffle = SlideshowShuffle()
        assertNull(shuffle.next(0, 1))
        assertEquals(1, shuffle.next(0, 2))
        assertNull(shuffle.next(1, 2))
        assertNull(shuffle.next(0, 0))
    }

    @Test
    fun orientationFitUsesViewportOrientation() {
        val image = Size(600f, 800f)
        assertEquals(ContentScale.FillWidth, ContentScale.fromPreferences(7, image, Size(800f, 1200f)))
        assertEquals(ContentScale.FillHeight, ContentScale.fromPreferences(7, image, Size(1200f, 800f)))
    }

    @Test
    fun slideshowUsesHorizontalPager() {
        assertEquals(ReadingModeType.SLIDESHOW, ReadingModeType.fromPreference(6))
        assertFalse(ReadingModeType.isWebtoon(ReadingModeType.SLIDESHOW))
        assertFalse(ReadingModeType.isVertical(ReadingModeType.SLIDESHOW))
    }
}
