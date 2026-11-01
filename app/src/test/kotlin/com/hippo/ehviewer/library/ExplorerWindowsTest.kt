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
    fun backgroundOpenKeepsTheCurrentWindow() {
        openLocal("photos")
        val active = ExplorerWindows.activeId
        val session = BrowseSession.localStack
        ExplorerWindows.openLocalInBackground(
            listOf(
                BrowseSession.LocalFrame(
                    rootId = 1L,
                    path = "/root/videos",
                    title = "videos",
                    relativePath = "videos",
                ),
            ),
            "Root",
        )
        assertEquals(2, ExplorerWindows.windows.size)
        assertEquals(active, ExplorerWindows.activeId)
        assertEquals("photos", ExplorerWindows.active()?.relativePath)
        assertEquals(session, BrowseSession.localStack)
        ExplorerWindows.openLocalInBackground(
            listOf(
                BrowseSession.LocalFrame(
                    rootId = 1L,
                    path = "/root/videos",
                    title = "videos",
                    relativePath = "videos",
                ),
            ),
            "Root",
        )
        assertEquals(2, ExplorerWindows.windows.size)
    }

    @Test
    fun windowOnPathPrefersTheWindowInFront() {
        openLocal("photos")
        val first = ExplorerWindows.activeId
        ExplorerWindows.duplicateActive()
        val front = ExplorerWindows.windowOnPath(ExplorerWindows.Kind.Local, 1L, "photos")
        assertEquals(ExplorerWindows.activeId, front?.id)
        assertNotEquals(first, front?.id)
        assertEquals(null, ExplorerWindows.windowOnPath(ExplorerWindows.Kind.Local, 1L, "missing"))
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

    @Test
    fun closeAllReleasesBindingsAndLeavesNoActiveWindow() {
        openLocal("photos")
        NavTabWindows.claim(NavTabWindows.Tab.Browse, ExplorerWindows.activeId!!)
        openLocal("videos")
        NavTabWindows.claim(NavTabWindows.Tab.Library, ExplorerWindows.activeId!!)

        ExplorerWindows.closeAll()

        assertEquals(emptyList<ExplorerWindows.Window>(), ExplorerWindows.windows.toList())
        assertEquals(null, ExplorerWindows.activeId)
        assertEquals(null, NavTabWindows.id(NavTabWindows.Tab.Browse))
        assertEquals(null, NavTabWindows.id(NavTabWindows.Tab.Library))
    }

    @Test
    fun foregroundFolderTabPreservesOutgoingPathAndClaimsNavigationOrigin() {
        openLocal("photos")
        val original = ExplorerWindows.activeId!!
        val stack = BrowseSession.localStack
        ExplorerWindows.setOrigin(original, fromHistory = true, fromLibrary = false)
        NavTabWindows.claim(NavTabWindows.Tab.History, original)
        ExplorerWindows.openLocalInBackground(
            listOf(BrowseSession.LocalFrame(1L, "/root/videos", "videos", "videos")),
            "Root",
        )
        val target = ExplorerWindows.windowOnPath(ExplorerWindows.Kind.Local, 1L, "videos")!!

        ExplorerWindows.activateFolderTab(target)

        assertEquals(target.id, ExplorerWindows.activeId)
        assertEquals("videos", BrowseSession.localStack.last().relativePath)
        assertEquals(true, ExplorerWindows.active()?.fromHistory)
        assertEquals(target.id, NavTabWindows.id(NavTabWindows.Tab.History))
        ExplorerWindows.activate(original)
        assertEquals(stack, BrowseSession.localStack)
        NavTabWindows.release(target.id)
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
