package com.hippo.ehviewer.library

import com.hippo.ehviewer.library.BrowseSession.LocalFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedExplorerPathsTest {
    @Test
    fun encodeParseRoundTrip() {
        val key = SavedExplorerPaths.encode(ExplorerWindows.Kind.Local, 4L, "/a/b/")
        val parsed = SavedExplorerPaths.parse(key)
        assertEquals(ExplorerWindows.Kind.Local, parsed?.kind)
        assertEquals(4L, parsed?.sourceId)
        assertEquals("a/b", parsed?.relativePath)
    }

    @Test
    fun webDavKeyKeepsNestedPath() {
        val parsed = SavedExplorerPaths.parse("webdav:9:photos/x")
        assertEquals(ExplorerWindows.Kind.WebDav, parsed?.kind)
        assertEquals(9L, parsed?.sourceId)
        assertEquals("photos/x", parsed?.relativePath)
    }

    @Test
    fun rejectsUnknownPrefix() {
        assertNull(SavedExplorerPaths.parse("lf:1:a"))
    }

    @Test
    fun displayRelativeIncludesZipInner() {
        val frame = LocalFrame(
            rootId = 1L,
            path = "/tmp/a.cbz",
            title = "Album",
            relativePath = "comics/a.cbz",
            zipInnerRel = "Album",
        )
        assertEquals("comics/a.cbz/Album", displayRelative(frame))
    }
}
