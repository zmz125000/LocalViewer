package com.hippo.ehviewer.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentExtractScheduleTest {
    @Test
    fun `background work waits until the page tree is listed`() {
        assertTrue(deferDocumentBackgroundWork(structureComplete = false))
        assertFalse(deferDocumentBackgroundWork(structureComplete = true))
    }

    @Test
    fun `only the viewport may extract while indexing`() {
        val visible = 0..0
        assertTrue(documentExtractIsVisible(0, visible, orgImg = false))
        assertFalse(documentExtractIsVisible(1, visible, orgImg = false))
        assertFalse(documentExtractIsVisible(2, visible, orgImg = false))
        assertTrue(documentExtractIsVisible(3, visible, orgImg = true))
    }

    @Test
    fun `dual-page viewport is visible on both slots`() {
        assertTrue(documentExtractIsVisible(4, 4..5, orgImg = false))
        assertTrue(documentExtractIsVisible(5, 4..5, orgImg = false))
        assertFalse(documentExtractIsVisible(6, 4..5, orgImg = false))
    }

    @Test
    fun `open race treats an unknown viewport as visible`() {
        assertTrue(documentExtractIsVisible(0, null, orgImg = false))
    }
}
