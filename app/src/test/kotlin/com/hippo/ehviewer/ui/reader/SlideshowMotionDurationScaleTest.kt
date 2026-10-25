package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.MotionDurationScale
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowMotionDurationScaleTest {
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
