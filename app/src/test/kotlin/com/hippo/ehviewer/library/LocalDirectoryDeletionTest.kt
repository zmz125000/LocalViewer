package com.hippo.ehviewer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDirectoryDeletionTest {
    private fun directory(name: String) = BrowseEntryRemote.Directory(name, hasVideo = false, hasGallery = true, presence = DirPresence.Navigable)
    private fun gallery(path: String) = BrowseEntryRemote.FolderGallery(path, path, 1, coverFileName = "a.jpg", imageFileNames = listOf("a.jpg"))

    @Test
    fun trashedFolderIsRemovedWhenParentConfirmsAbsenceWithoutProbingAChildUri() = runBlocking {
        val cached = listOf(directory("Album + 100%"), gallery("Album + 100%"), directory("Keep"))
        val live = listOf(RemoteChild("Keep", true), RemoteChild(".trash", true))
        var queries = 0
        assertEquals(
            setOf("Album + 100%"),
            confirmedMissingLocalDirectoryRoots(cached, live) {
                queries++
                setOf("Keep", ".trash")
            },
        )
        assertEquals(1, queries)
    }

    @Test
    fun coldStartSnapshotRequiresNoInProcessMutationHint() = runBlocking {
        val diskSnapshot = listOf(directory("Deleted"), gallery("Deleted/sub"), BrowseEntryRemote.VideoFile("@Deleted", "Deleted/clip.mp4"))
        assertEquals(setOf("Deleted"), confirmedMissingLocalDirectoryRoots(diskSnapshot, emptyList()) { emptySet() })
    }

    @Test
    fun galleryOnlyAndPromotedRowsAlsoIdentifyTheirDeletedPhysicalFolder() = runBlocking {
        val snapshot = listOf(gallery("Gallery"), gallery("Promoted/leaf"), BrowseEntryRemote.ArchiveGallery("book.zip", "book.zip", parentRelativeName = "ArchiveBranch/sub"), gallery(""))
        assertEquals(setOf("Gallery", "Promoted", "ArchiveBranch"), confirmedMissingLocalDirectoryRoots(snapshot, emptyList()) { emptySet() })
    }

    @Test
    fun incompleteFirstListingCannotRemoveAFolderStillFoundByVerification() = runBlocking {
        val cached = listOf(directory("Keep"), directory("Gone"))
        assertEquals(setOf("Gone"), confirmedMissingLocalDirectoryRoots(cached, emptyList()) { setOf("Keep") })
    }

    @Test
    fun nullDeniedOrLoadingVerificationKeepsTheCachedFolder() = runBlocking {
        assertTrue(confirmedMissingLocalDirectoryRoots(listOf(directory("Keep")), emptyList()) { null }.isEmpty())
    }

    @Test
    fun unchangedFolderAndZipAsDirectoryDoNotNeedVerification() = runBlocking {
        val cached = listOf(directory("Keep"), directory("book.zip"), gallery("book.zip/chapter"))
        val live = listOf(RemoteChild("Keep", true), RemoteChild("book.zip", false))
        assertTrue(confirmedMissingLocalDirectoryRoots(cached, live) { error("No missing names") }.isEmpty())
    }

    @Test
    fun cancellationDuringVerificationIsNotTreatedAsDeletion() = runBlocking {
        try {
            confirmedMissingLocalDirectoryRoots(listOf(directory("Keep")), emptyList()) { throw CancellationException("Stopped") }
            throw AssertionError("Cancellation should propagate")
        } catch (_: CancellationException) {
            // No deletion plan returned.
        }
    }
}
