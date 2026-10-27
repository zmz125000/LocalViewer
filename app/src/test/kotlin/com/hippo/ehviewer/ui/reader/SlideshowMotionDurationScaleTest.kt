package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.MotionDurationScale
import eu.kanade.tachiyomi.ui.reader.setting.PageTransitionType
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowMotionDurationScaleTest {
    @Test
    fun slideshowFadesTakeFiveTimesDurationAndKeepSystemAnimatorScale() {
        val system = object : MotionDurationScale {
            override var scaleFactor = 1f
        }
        for (transition in listOf(PageTransitionType.FADE, PageTransitionType.FADE_BLEND)) {
            val slideshow = SlideshowMotionDurationScale(system, transition)
            system.scaleFactor = 1f
            assertEquals(5f, slideshow.scaleFactor, 0f)
            system.scaleFactor = 2f
            assertEquals(10f, slideshow.scaleFactor, 0f)
            system.scaleFactor = 0f
            assertEquals(0f, slideshow.scaleFactor, 0f)
            assertEquals(5f, SlideshowMotionDurationScale(null, transition).scaleFactor, 0f)
        }
    }

    @Test
    fun slideshowTriplesDurationAndKeepsSystemAnimatorScale() {
        val system = object : MotionDurationScale {
            override var scaleFactor = 1f
        }
        val slideshow = SlideshowMotionDurationScale(system)
        assertEquals(3f, slideshow.scaleFactor, 0f)
        system.scaleFactor = 2f
        assertEquals(6f, slideshow.scaleFactor, 0f)
        system.scaleFactor = 0f
        assertEquals(0f, slideshow.scaleFactor, 0f)
        assertEquals(3f, SlideshowMotionDurationScale(null).scaleFactor, 0f)
    }
}
