package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBackHistoryTest {
    @Test
    fun backFromDeepestWritesThatFolderOnly() {
        // C(1) → B(2) → A(3). A back to B writes A. B back to C does not write B.
        var deepest = 1
        deepest = FolderBackHistory.afterNavigate(1, 2, deepest)
        deepest = FolderBackHistory.afterNavigate(2, 3, deepest)
        assertEquals(3, deepest)
        assertTrue(FolderBackHistory.shouldWriteDeepest(3, deepest, landsOnFolder = true))
        assertFalse(FolderBackHistory.shouldWriteDeepest(2, deepest, landsOnFolder = true))
        assertFalse(FolderBackHistory.shouldWriteDeepest(1, deepest, landsOnFolder = false))
    }

    @Test
    fun siblingEnterIsANewDeepest() {
        var deepest = 1
        deepest = FolderBackHistory.afterNavigate(1, 2, deepest)
        deepest = FolderBackHistory.afterNavigate(2, 3, deepest)
        assertTrue(FolderBackHistory.shouldWriteDeepest(3, deepest, landsOnFolder = true))
        // Still at B. Enter sibling A2, then back writes A2.
        deepest = FolderBackHistory.afterNavigate(2, 3, deepest)
        assertEquals(3, deepest)
        assertTrue(FolderBackHistory.shouldWriteDeepest(3, deepest, landsOnFolder = true))
        // Back to C does not write B. Enter sibling B2, then back writes B2.
        assertFalse(FolderBackHistory.shouldWriteDeepest(2, deepest, landsOnFolder = true))
        deepest = FolderBackHistory.afterNavigate(1, 2, deepest)
        assertEquals(2, deepest)
        assertTrue(FolderBackHistory.shouldWriteDeepest(2, deepest, landsOnFolder = true))
    }

    @Test
    fun shrinkDoesNotMoveDeepest() {
        val deepest = FolderBackHistory.afterNavigate(3, 1, deepest = 3)
        assertEquals(3, deepest)
        assertFalse(FolderBackHistory.shouldWriteDeepest(1, deepest, landsOnFolder = true))
    }

    @Test
    fun rootBackDoesNotWrite() {
        assertFalse(FolderBackHistory.shouldWriteDeepest(0, deepest = 0, landsOnFolder = false))
        assertFalse(FolderBackHistory.shouldWriteDeepest(1, deepest = 1, landsOnFolder = false))
    }
}
