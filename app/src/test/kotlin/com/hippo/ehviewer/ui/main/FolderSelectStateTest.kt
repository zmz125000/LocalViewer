package com.hippo.ehviewer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FolderSelectStateTest {
    @Before
    fun resetHost() {
        FolderSelectHost.resetForTest()
    }

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

    @Test
    fun backLeavesSelectModeWithoutClearingTheSnackbar() {
        val state = FolderSelectState()
        state.bindFolder("a", "Photos")
        state.enter(pick("1", "one"))
        state.toggle(pick("2", "two"))
        state.exitSelectMode()
        assertFalse(state.selecting)
        assertEquals(listOf("1", "2"), state.picks.map { it.key })
        assertEquals("Photos", FolderSelectHost.selectionSnack?.folderName)
        assertEquals(2, FolderSelectHost.selectionSnack?.count)

        state.bindFolder("b", "Camera")
        assertEquals("Photos", FolderSelectHost.selectionSnack?.folderName)
        assertEquals(2, FolderSelectHost.selectionSnack?.count)
        assertEquals(listOf("1", "2"), FolderSelectHost.selectionPicks.map { it.key })

        val restored = FolderSelectState()
        restored.bindFolder("b", "Camera")
        assertEquals("Photos", FolderSelectHost.selectionSnack?.folderName)

        FolderSelectHost.cancelShownSelection()
        assertNull(FolderSelectHost.selectionSnack)
        restored.bindFolder("a", "Photos")
        assertTrue(restored.picks.isEmpty())
        assertFalse(restored.selecting)
    }

    private fun pick(key: String, title: String) = FolderPick(
        key = key,
        title = title,
        shareHttp = null,
        convertStill = null,
        saveInto = null,
    )
}
