package com.hippo.ehviewer.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerWalkTest {
    @Test
    fun `media-only scan skips directory walk after MediaStore index`() {
        assertFalse(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = true, includeArchives = false))
    }

    @Test
    fun `archive mode still walks after MediaStore to find zips`() {
        assertTrue(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = true, includeArchives = true))
    }

    @Test
    fun `without MediaStore the tree is always walked`() {
        assertTrue(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = false, includeArchives = false))
        assertTrue(LibraryScanner.needsDirectoryWalk(mediaStoreIndexed = false, includeArchives = true))
    }
}
