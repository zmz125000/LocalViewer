package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionStateTest {
    @Test
    fun `seek landing does not hide reader chrome`() {
        val state = ReaderInteractionState(startPage = 2)

        state.beginSeek(18)
        state.finishSeek(18)

        assertFalse(state.observePage(18))
    }

    @Test
    fun `settings relayout does not hide reader chrome`() {
        val state = ReaderInteractionState(startPage = 4)

        state.beginSettingsChange()
        assertFalse(state.observePage(5))
        state.finishSettingsChange()

        assertTrue(state.observePage(6))
    }

    @Test
    fun `ordinary page selection hides reader chrome`() {
        val state = ReaderInteractionState(startPage = 7)

        assertTrue(state.observePage(8))
    }
}
