package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseWheelTest {
    @Test
    fun verticalWheelWinsUnlessHorizontalIsLarger() {
        assertEquals(1.2f, readingWheelLines(0.2f, 1.2f))
        assertEquals(-0.8f, readingWheelLines(-0.8f, 0.1f))
        assertEquals(0.4f, readingWheelLines(0.4f, 0.4f))
    }

    @Test
    fun fractionalNotchesBecomeWholePages() {
        val notches = WheelNotches()
        assertEquals(0, notches.push(0.4f))
        assertEquals(1, notches.push(0.7f))
        assertEquals(0, notches.push(0.2f))
        assertEquals(2, notches.push(2.5f))
    }

    @Test
    fun backwardRemainderIsKept() {
        val notches = WheelNotches()
        assertEquals(0, notches.push(-0.5f))
        assertEquals(-1, notches.push(-0.5f))
        assertEquals(-1, notches.push(-1f))
        assertEquals(1, notches.push(1.5f))
    }
}
