package com.hippo.ehviewer.ui.reader

import eu.kanade.tachiyomi.ui.reader.setting.PageTransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTransitionTypeTest {
    @Test
    fun existingPreferencesKeepTheirMeaning() {
        assertEquals(PageTransitionType.DEFAULT, PageTransitionType.fromPreference(0))
        assertEquals(PageTransitionType.FADE, PageTransitionType.fromPreference(1))
        assertEquals(PageTransitionType.NONE, PageTransitionType.fromPreference(2))
        assertEquals(PageTransitionType.FADE_BLEND, PageTransitionType.fromPreference(3))
        assertEquals(PageTransitionType.SLIDE, PageTransitionType.fromPreference(4))
        assertEquals(PageTransitionType.DEFAULT, PageTransitionType.fromPreference(99))
    }

    @Test
    fun slideshowSupportsEveryTransitionAndCanInheritGeneralChoice() {
        for (type in PageTransitionType.entries) {
            assertEquals(type, PageTransitionType.resolve(0, type.prefValue, slideshow = true))
            assertEquals(type, PageTransitionType.resolve(type.prefValue, -1, slideshow = true))
            assertEquals(type, PageTransitionType.resolve(type.prefValue, 2, slideshow = false))
        }
    }

    @Test
    fun bothFadesWaitForImagesAndSlideshowWaitsForEveryType() {
        for (type in PageTransitionType.entries) {
            assertTrue(type.needsPageReady(slideshow = true))
            if (type == PageTransitionType.FADE || type == PageTransitionType.FADE_BLEND) {
                assertTrue(type.needsPageReady(slideshow = false))
            } else {
                assertFalse(type.needsPageReady(slideshow = false))
            }
        }
    }

    @Test
    fun easingStartsQuicklyAndSlowsBeforeStoppingWithoutOvershoot() {
        assertEquals(0f, PageTransitionEaseOut.transform(0f), 0.0001f)
        assertEquals(1f, PageTransitionEaseOut.transform(1f), 0.0001f)
        var previous = 0f
        for (step in 1..100) {
            val value = PageTransitionEaseOut.transform(step / 100f)
            assertTrue(value >= previous && value <= 1f)
            previous = value
        }
        val earlyMotion = PageTransitionEaseOut.transform(0.1f)
        val finalMotion = 1f - PageTransitionEaseOut.transform(0.9f)
        assertTrue(PageTransitionEaseOut.transform(0.5f) > 0.5f)
        assertTrue(finalMotion < earlyMotion / 10f)
    }
}
