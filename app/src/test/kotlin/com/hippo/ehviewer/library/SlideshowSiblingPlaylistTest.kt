package com.hippo.ehviewer.library

import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowSiblingPlaylistTest {
    @After
    fun tearDown() {
        ReaderGalleryPlaylist.clear()
    }

    @Test
    fun multipleGalleriesWrapAndSkipEbooks() {
        ReaderGalleryPlaylist.set(
            listOf(
                ReaderGalleryPlaylist.Item.LocalFolder("/galleries/a"),
                ReaderGalleryPlaylist.Item.Archive("/galleries/novel.epub"),
                ReaderGalleryPlaylist.Item.Archive("/galleries/notes.txt"),
                ReaderGalleryPlaylist.Item.LocalFolder("/galleries/b"),
                ReaderGalleryPlaylist.Item.Archive("/galleries/comic.cbz"),
            ),
        )
        assertEquals(
            ReaderScreenArgs.LocalFolder("/galleries/b", page = 0),
            opened(ReaderScreenArgs.LocalFolder("/galleries/a")),
        )
        assertEquals(
            ReaderScreenArgs.Archive("/galleries/comic.cbz", page = 0),
            opened(ReaderScreenArgs.LocalFolder("/galleries/b")),
        )
        assertEquals(
            ReaderScreenArgs.LocalFolder("/galleries/a", page = 0),
            opened(ReaderScreenArgs.Archive("/galleries/comic.cbz")),
        )
        assertEquals(
            ReaderScreenArgs.LocalFolder("/galleries/b", page = 0),
            opened(ReaderScreenArgs.Archive("/galleries/novel.epub")),
        )
    }

    @Test
    fun singleGalleryRepeatsItself() {
        ReaderGalleryPlaylist.set(
            listOf(ReaderGalleryPlaylist.Item.LocalFolder("/galleries/only")),
        )
        assertEquals(
            SlideshowHop.RepeatSelf,
            ReaderGalleryPlaylist.slideshowHop(ReaderScreenArgs.LocalFolder("/galleries/only")),
        )
    }

    @Test
    fun ebookBesideOneGalleryRepeatsTheGallery() {
        ReaderGalleryPlaylist.set(
            listOf(
                ReaderGalleryPlaylist.Item.SmbFolder(4L, "share/a", emptyList()),
                ReaderGalleryPlaylist.Item.SmbStreamArchive(4L, "share/book.fb2"),
            ),
        )
        assertEquals(
            SlideshowHop.RepeatSelf,
            ReaderGalleryPlaylist.slideshowHop(ReaderScreenArgs.SmbFolder(4L, "share/a", emptyList())),
        )
        assertNull(ReaderGalleryPlaylist.sibling(ReaderScreenArgs.SmbStreamArchive(4L, "share/book.fb2"), next = true))
    }

    @Test
    fun manualSiblingStillStopsAtEndAndKeepsEbooks() {
        ReaderGalleryPlaylist.set(
            listOf(
                ReaderGalleryPlaylist.Item.LocalFolder("/galleries/a"),
                ReaderGalleryPlaylist.Item.Archive("/galleries/novel.epub"),
            ),
        )
        assertEquals(
            ReaderScreenArgs.Archive("/galleries/novel.epub", page = -1),
            ReaderGalleryPlaylist.sibling(ReaderScreenArgs.LocalFolder("/galleries/a"), next = true),
        )
        assertNull(ReaderGalleryPlaylist.sibling(ReaderScreenArgs.Archive("/galleries/novel.epub"), next = true))
    }

    @Test
    fun pickWrapsEligibleIndexes() {
        val ebook = setOf(1, 3)
        assertEquals(2, pickSlideshowSiblingIndex(5, 0, next = true) { it in ebook })
        assertEquals(0, pickSlideshowSiblingIndex(5, 4, next = true) { it in ebook })
        assertEquals(4, pickSlideshowSiblingIndex(5, 0, next = false) { it in ebook })
        assertNull(pickSlideshowSiblingIndex(3, 0, next = true) { it != 0 })
        assertEquals(2, pickSlideshowSiblingIndex(3, 1, next = true) { it == 1 })
    }

    private fun opened(args: ReaderScreenArgs): ReaderScreenArgs {
        val hop = ReaderGalleryPlaylist.slideshowHop(args)
        return (hop as SlideshowHop.Open).args
    }
}
