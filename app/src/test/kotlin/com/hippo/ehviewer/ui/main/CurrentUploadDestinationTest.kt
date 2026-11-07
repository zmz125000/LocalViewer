package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.library.ExplorerWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentUploadDestinationTest {
    private fun place(path: String, kind: ExplorerWindows.Kind = ExplorerWindows.Kind.Smb, root: String? = null) = FolderPlace(kind, 1, path, rootPath = root)

    @Test
    fun nonFolderViewsAreRejected() {
        assertNull(currentUploadFolderPath(null))
    }

    @Test
    fun normalFoldersAndConfiguredSourceRootsAreAccepted() {
        assertEquals("", currentUploadFolderPath(place("")))
        assertEquals("photos/album", currentUploadFolderPath(place("photos/album")))
        assertEquals("photos/album", currentUploadFolderPath(place("photos\\album", ExplorerWindows.Kind.WebDav)))
        assertEquals("Album +", currentUploadFolderPath(place("Album +", ExplorerWindows.Kind.Local, "content:/provider/tree/root/document/root")))
    }

    @Test
    fun zipRootsAndMembersCannotBeUsedAsUploadDestinations() {
        for (path in listOf("book.zip", "photos/book.zip/chapter", "book.cbz/inside")) {
            assertNull(currentUploadFolderPath(place(path)))
        }
        assertNull(currentUploadFolderPath(place("", ExplorerWindows.Kind.Local, "zipfile:content:/provider/book.zip!chapter")))
    }

    @Test
    fun deviceMediaIsNotAWritableSafFolder() {
        assertNull(currentUploadFolderPath(place("Pictures", ExplorerWindows.Kind.Local, "mediastore:/")))
    }

    @Test
    fun traversalIsRejected() {
        for (path in listOf("../album", "photos/../album", "photos/./album")) {
            assertNull(currentUploadFolderPath(place(path)))
        }
    }
}
