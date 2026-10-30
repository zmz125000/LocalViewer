package com.hippo.ehviewer.library

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashFilesTest {
    private fun file(path: String, directory: Boolean = false, sourceId: Long = 1) = TransferFile(TransferLocation(ExplorerWindows.Kind.Smb, sourceId, path), path.substringAfterLast('/'), directory, 123, 456)

    @Test
    fun trashFolderAndAllDescendantsAreProtectedAcrossPathStyles() {
        for (path in listOf(".trash", "photos/.trash", ".trash/2026-10-30/photo.jpg", "share/photos/.trash/2026-10-30/folder/sub", "share\\photos\\.TRASH\\2026-10-30\\photo.jpg")) {
            assertTrue(path, isInsideTrashPath(path))
            try {
                trashDestinationDirectory(path, "2026-10-30")
                throw AssertionError("Allowed deleting a protected trash item: $path")
            } catch (_: TrashProtectedException) {
                // The common destination planner must reject before any remote mutation.
            }
        }
        for (path in listOf("photos/trash/a.jpg", "photos/.trashcan/a.jpg", "photos/my.trash/a.jpg")) {
            assertFalse(path, isInsideTrashPath(path))
        }
    }

    @Test
    fun wholeFolderIsOneRootAndSelectedDescendantsAreNotMovedAgain() {
        val roots = trashSelectionRoots(listOf(file("photos", true), file("photos/a.jpg"), file("photos/sub", true), file("photos2/b.jpg"), file("photos", true), file("photos/a.jpg", sourceId = 2)))
        assertEquals(listOf("photos", "photos2/b.jpg", "photos/a.jpg"), roots.map { it.source.path })
        assertTrue(roots.first().directory)
    }

    @Test
    fun zipRootIsOneFileAndMemberIsNeverReplacedWithItsBackingZip() {
        val root = trashSelectionRoots(listOf(file("photos/album.zip", true))).single()
        assertFalse(root.directory)
        val member = trashSelectionRoots(listOf(file("photos/album.zip/page.jpg"))).single()
        assertEquals("photos/album.zip/page.jpg", member.source.path)
        assertTrue(isTrashArchiveMember(member.source.path))
        assertTrue(isTrashArchiveMember("share\\album.CBZ\\page.jpg"))
        assertFalse(isTrashArchiveMember("share/album.zip"))
    }

    @Test
    fun trashIsInsideEachItemsParentAndDateDirectoryIsStable() {
        assertEquals(".trash/2026-10-30", trashDestinationDirectory("photo.jpg", "2026-10-30"))
        assertEquals("share/photos/.trash/2026-10-30", trashDestinationDirectory("share/photos/album", "2026-10-30"))
        assertEquals("share/photos/.trash/2026-10-30", trashDestinationDirectory("share\\photos\\photo.jpg", "2026-10-30"))
        for (path in listOf("", "/", ".", "..", "photos/../a.jpg", "photos/.trash")) {
            try {
                trashDestinationDirectory(path, "2026-10-30")
                throw AssertionError("Accepted invalid trash source: $path")
            } catch (_: IllegalArgumentException) {
                // A root or the trash folder itself must never be moved into itself.
            }
        }
    }

    @Test
    fun exclusiveRenameRetriesAllCollisionsWithoutReplacingAnyExistingEntry() = runBlocking {
        val existing = (1..1000).mapTo(HashSet()) { if (it == 1) "photo.jpg" else "photo ($it).jpg" }
        val previous = existing.toSet()
        val result = moveToTrashWithoutOverwrite("photo.jpg", false) { name ->
            if (!existing.add(name)) throw TransferNameConflict(name)
        }
        assertEquals("photo (1001).jpg", result)
        assertTrue(existing.containsAll(previous))
        assertEquals(previous.size + 1, existing.size)
        val names = mutableListOf<String>()
        assertEquals(
            "album.v1 (2)",
            moveToTrashWithoutOverwrite("album.v1", true) { name ->
                names += name
                if (names.size == 1) throw TransferNameConflict(name)
            },
        )
    }

    @Test
    fun denialUnsupportedAndCancellationNeverFallBackOrRetry() = runBlocking {
        for (failure in listOf(IOException("Access denied"), UnsupportedOperationException("No rename"), CancellationException("Stopped"))) {
            var attempts = 0
            try {
                moveToTrashWithoutOverwrite("photo.jpg", false) {
                    attempts++
                    throw failure
                }
                throw AssertionError("Move should have failed")
            } catch (error: Exception) {
                assertTrue(error === failure)
                assertEquals(1, attempts)
            }
        }
    }
}
