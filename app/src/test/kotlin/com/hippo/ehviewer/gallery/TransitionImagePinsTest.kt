package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionImagePinsTest {
    private class ImageRef(var refs: Int = 1) {
        fun retain(): Boolean = if (refs == 0) {
            false
        } else {
            refs++
            true
        }
        fun release() {
            check(refs > 0)
            refs--
        }
    }
    private fun pins() = TransitionImagePins<ImageRef>({ it.retain() }, { it.release() })

    @Test
    fun destinationSurvivesCacheEvictionUntilTransitionCompletes() {
        val image = ImageRef()
        val pins = pins()
        pins.setWanted(setOf(20)) { image }
        image.release() // LRU evicts the bitmap before the pager composes its painter.
        assertEquals(1, image.refs)
        pins.setWanted(emptySet()) { null }
        assertEquals(0, image.refs)
    }

    @Test
    fun newDecodeIsPinnedBeforeCacheAndStatusPublication() {
        val image = ImageRef()
        val pins = pins()
        pins.setWanted(setOf(20)) { null }
        pins.publish(20, image) {
            assertEquals(2, image.refs)
            image.release() // Even an immediate cache eviction cannot recycle this frame.
        }
        assertEquals(1, image.refs)
        pins.clear()
        assertEquals(0, image.refs)
    }

    @Test
    fun recycledReadyImageIsReportedForRedecode() {
        val image = ImageRef(0)
        val stale = pins().setWanted(setOf(20)) { image }
        assertSame(image, stale[20])
    }

    @Test
    fun replacementAndCancellationReleaseEachOwnershipExactlyOnce() {
        val pins = pins()
        val old = ImageRef()
        val next = ImageRef()
        pins.setWanted(setOf(20)) { old }
        pins.setWanted(setOf(20)) { old }
        assertEquals(2, old.refs)
        pins.publish(20, next) {}
        assertEquals(1, old.refs)
        assertEquals(2, next.refs)
        pins.clear()
        pins.clear()
        assertEquals(1, next.refs)
    }

    @Test
    fun decoderRestartRetainsDemandButDropsOldImages() {
        val pins = pins()
        val old = ImageRef()
        pins.setWanted(setOf(20)) { old }
        pins.releaseImages()
        assertEquals(1, old.refs)
        val replacement = ImageRef()
        pins.publish(20, replacement) { assertEquals(2, replacement.refs) }
        pins.setWanted(emptySet()) { null }
        assertEquals(1, replacement.refs)
        assertTrue(pins.setWanted(emptySet()) { null }.isEmpty())
    }
}
