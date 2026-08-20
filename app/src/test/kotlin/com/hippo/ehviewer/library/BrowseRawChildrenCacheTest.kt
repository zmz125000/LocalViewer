package com.hippo.ehviewer.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseRawChildrenCacheTest {
    @After
    fun tearDown() {
        BrowseSession.invalidateLocalListing()
        BrowseSession.invalidateSmbListing(7)
    }

    @Test
    fun `parent peek is reused when entering the same child path`() {
        val child = "/root/S"
        var loads = 0
        val first = BrowseSession.rememberLocalRawChildren(child) {
            loads++
            listOf(RemoteChild("page.jpg", isDirectory = false))
        }
        val second = BrowseSession.rememberLocalRawChildren(child) {
            loads++
            error("child listed twice")
        }
        assertEquals(1, loads)
        assertEquals(first, second)
    }

    @Test
    fun `refreshing parent drops descendant peeks`() {
        val parent = "/root"
        val child = "/root/S"
        BrowseSession.rememberLocalRawChildren(child) {
            listOf(RemoteChild("leaf", isDirectory = true))
        }
        BrowseSession.invalidateLocalListing(parent)
        var loads = 0
        BrowseSession.rememberLocalRawChildren(child) {
            loads++
            emptyList()
        }
        assertEquals(1, loads)
    }

    @Test
    fun `sibling path is not dropped with parent refresh`() {
        BrowseSession.rememberLocalRawChildren("/root2/S") {
            listOf(RemoteChild("keep", isDirectory = false))
        }
        BrowseSession.invalidateLocalListing("/root")
        var loads = 0
        BrowseSession.rememberLocalRawChildren("/root2/S") {
            loads++
            error("sibling peek dropped")
        }
        assertEquals(0, loads)
    }

    @Test
    fun `protected system names are stripped from remembered children`() {
        val children = BrowseSession.rememberLocalRawChildren("/root/synology") {
            listOf(
                RemoteChild("gallery", isDirectory = true),
                RemoteChild("@eaDir", isDirectory = true),
            )
        }
        assertEquals(listOf("gallery"), children.map { it.name })
    }

    @Test
    fun `smb peek keys ignore slash style`() = runBlocking {
        var loads = 0
        BrowseSession.rememberSmbRawChildren(7, "foo\\bar") {
            loads++
            listOf(RemoteChild("a.jpg", isDirectory = false))
        }
        BrowseSession.rememberSmbRawChildren(7, "foo/bar") {
            loads++
            error("slash variant listed twice")
        }
        assertEquals(1, loads)
        BrowseSession.invalidateSmbRawChildren(7, "foo")
        var after = 0
        BrowseSession.rememberSmbRawChildren(7, "foo/bar") {
            after++
            emptyList()
        }
        assertEquals(1, after)
    }
}
