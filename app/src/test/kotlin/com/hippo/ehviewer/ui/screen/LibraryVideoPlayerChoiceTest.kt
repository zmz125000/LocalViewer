package com.hippo.ehviewer.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryVideoPlayerChoiceTest {
    @Test
    fun mainMedia3CoversBothLibraryVideoModes() {
        assertTrue(libraryVideoFileUsesMedia3(media3 = true, media3ForLibrary = false, LibraryVideoMode.Files))
        assertTrue(libraryVideoFileUsesMedia3(media3 = true, media3ForLibrary = false, LibraryVideoMode.Folders))
    }

    @Test
    fun libraryToggleCoversAllVideosListAndVideoFolders() {
        assertTrue(
            libraryVideoFileUsesMedia3(media3 = false, media3ForLibrary = true, LibraryVideoMode.Files),
        )
        assertTrue(
            libraryVideoFileUsesMedia3(
                media3 = false,
                media3ForLibrary = true,
                videoMode = LibraryVideoMode.Folders,
                libraryVideoFolder = true,
            ),
        )
        assertFalse(
            libraryVideoFileUsesMedia3(media3 = false, media3ForLibrary = true, LibraryVideoMode.Folders),
        )
        assertFalse(
            libraryVideoFileUsesMedia3(
                media3 = false,
                media3ForLibrary = true,
                videoMode = LibraryVideoMode.Folders,
                libraryVideoFolder = true,
                longPress = true,
            ),
        )
    }

    @Test
    fun longPressUsesTheOtherPlayer() {
        assertFalse(
            libraryVideoFileUsesMedia3(
                media3 = false,
                media3ForLibrary = true,
                videoMode = LibraryVideoMode.Files,
                longPress = true,
            ),
        )
        assertTrue(
            libraryVideoFileUsesMedia3(
                media3 = false,
                media3ForLibrary = false,
                videoMode = LibraryVideoMode.Files,
                longPress = true,
            ),
        )
    }

    @Test
    fun localFilesToggleCoversFolderBrowse() {
        assertTrue(localFolderVideoUsesMedia3(media3 = true, media3ForLocalFiles = false))
        assertTrue(localFolderVideoUsesMedia3(media3 = false, media3ForLocalFiles = true))
        assertFalse(localFolderVideoUsesMedia3(media3 = false, media3ForLocalFiles = false))
        assertFalse(
            localFolderVideoUsesMedia3(media3 = false, media3ForLocalFiles = true, longPress = true),
        )
        assertTrue(
            localFolderVideoUsesMedia3(media3 = false, media3ForLocalFiles = false, longPress = true),
        )
    }
}
