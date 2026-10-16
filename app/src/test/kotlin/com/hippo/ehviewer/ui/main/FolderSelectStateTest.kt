package com.hippo.ehviewer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSelectStateTest {
    @Test
    fun overflowSelectMarksThatItemAndEntersSelectMode() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("1", "one"))
        assertTrue(state.selecting)
        assertEquals(listOf("1"), state.picks.map { it.key })
        state.enter(pick("1", "one"))
        assertEquals(1, state.picks.size)
    }

    @Test
    fun eachFolderKeepsItsOwnSelection() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("1", "one"))
        state.toggle(pick("2", "two"))
        state.sectionKeys = mapOf(BrowseFolderSection.Files to listOf("1", "2"))

        state.bindFolder("b")
        assertFalse(state.selecting)
        assertTrue(state.picks.isEmpty())
        assertTrue(state.sectionKeys.isEmpty())
        state.enter(pick("9", "nine"))

        state.bindFolder("a")
        assertTrue(state.selecting)
        assertEquals(listOf("1", "2"), state.picks.map { it.key })

        state.bindFolder("b")
        assertTrue(state.selecting)
        assertEquals(listOf("9"), state.picks.map { it.key })
    }

    @Test
    fun cancelClearsOnlyTheCurrentFolder() {
        val state = FolderSelectState()
        state.bindFolder("a")
        state.enter(pick("1", "one"))
        state.bindFolder("b")
        state.enter(pick("9", "nine"))
        state.cancel()
        assertFalse(state.selecting)
        assertTrue(state.picks.isEmpty())

        state.bindFolder("a")
        assertTrue(state.selecting)
        assertEquals(listOf("1"), state.picks.map { it.key })
        state.bindFolder("b")
        assertFalse(state.selecting)
        assertTrue(state.picks.isEmpty())
    }

    private fun pick(key: String, title: String) = FolderPick(
        key = key,
        title = title,
        shareHttp = null,
        convertStill = null,
        saveInto = null,
    )
}
