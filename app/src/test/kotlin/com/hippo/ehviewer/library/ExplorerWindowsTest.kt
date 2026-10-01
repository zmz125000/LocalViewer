package com.hippo.ehviewer.library

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class ExplorerWindowsTest {
    @Before
    fun setUp() {
        ExplorerWindows.resetForTest()
        BrowseSession.localStack = emptyList()
    }

    @After
    fun tearDown() {
        ExplorerWindows.resetForTest()
        BrowseSession.localStack = emptyList()
    }

    @Test
    fun secondOpenOfSamePathReusesWindow() {
        openLocal("photos/album")
        openLocal("photos/album")
        assertEquals(1, ExplorerWindows.windows.size)
        assertEquals("photos/album", ExplorerWindows.active()?.relativePath)
    }

    @Test
    fun differentPathSpawnsAnotherWindow() {
        openLocal("photos")
        openLocal("videos")
        assertEquals(2, ExplorerWindows.windows.size)
        assertEquals("videos", ExplorerWindows.active()?.relativePath)
    }

    @Test
    fun openingExistingPathSwitchesToThatWindow() {
        openLocal("photos")
        openLocal("videos")
        val photosId = ExplorerWindows.windows.first { it.relativePath == "photos" }.id
        openLocal("photos")
        assertEquals(2, ExplorerWindows.windows.size)
        assertEquals(photosId, ExplorerWindows.activeId)
    }

    @Test
    fun duplicateIsTheOnlyWayToRepeatAPath() {
        openLocal("photos")
        val first = ExplorerWindows.activeId
        val copy = ExplorerWindows.duplicateActive()
        assertEquals(2, ExplorerWindows.windows.size)
        assertNotEquals(first, copy?.id)
        assertEquals("photos", copy?.relativePath)
    }

    private fun openLocal(relativePath: String) {
        val leaf = relativePath.substringAfterLast('/').ifEmpty { "Root" }
        ExplorerWindows.prepareSpawn()
        BrowseSession.localStack = listOf(
            BrowseSession.LocalFrame(
                rootId = 1L,
                path = "/root/$relativePath",
                title = leaf,
                relativePath = relativePath,
            ),
        )
        ExplorerWindows.finishLocalSpawn("Root")
    }
}
