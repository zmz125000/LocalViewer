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
    fun galleryCleanupKeepsImagePlanAndNeverRecursivelyCollectsOtherContents() = runBlocking {
        val image = file("share/album/1.jpg")
        val cleanup = file("share/album", true).copy(deleteIfEmpty = true)
        val roots = trashSelectionRoots(listOf(image, cleanup))
        assertEquals(listOf(image.source, cleanup.source), roots.map { it.source })
        val result = collectRemovalFiles(roots, FileRemovalAction.Delete, children = { error("Gallery cleanup must not collect other contents") })
        assertEquals(listOf(image.source, cleanup.source), result.map { it.source })
        assertTrue(result.last().deleteIfEmpty)
    }

    @Test
    fun galleryCleanupDeletesOnlyAfterFreshEmptyListing() = runBlocking {
        val cleanup = file("share/album", true).copy(deleteIfEmpty = true)
        val calls = mutableListOf<String>()
        assertTrue(
            PermanentFiles.delete(
                cleanup,
                FileRemovalAction.Delete,
                TransferControl(),
                onDelete = { calls += "attempt" },
                isEmpty = {
                    assertEquals(cleanup.source, it)
                    calls += "list"
                    true
                },
                remove = { calls += "delete" },
            ),
        )
        assertEquals(listOf("list", "attempt", "delete"), calls)
    }

    @Test
    fun galleryCleanupSkipsAnyRemainingFileOrSubfolderWithoutMarkingUnavailable() = runBlocking {
        val cleanup = file("share/album", true).copy(deleteIfEmpty = true)
        for (remaining in listOf(file("share/album/notes.txt"), file("share/album/failed.jpg"), file("share/album/empty-subfolder", true), file("share/album/.trash", true))) {
            assertFalse(
                PermanentFiles.delete(
                    cleanup,
                    FileRemovalAction.Delete,
                    TransferControl(),
                    onDelete = { error("Skipped cleanup must not mark source unavailable") },
                    isEmpty = { listOf(remaining).isEmpty() },
                    remove = { error("Must preserve remaining contents") },
                ),
            )
        }
    }

    @Test
    fun galleryCleanupDoesNotDeleteWhenFreshListingFails() = runBlocking {
        try {
            PermanentFiles.delete(
                file("share/album", true).copy(deleteIfEmpty = true),
                FileRemovalAction.Delete,
                TransferControl(),
                onDelete = { error("Listing failure must not mark source unavailable") },
                isEmpty = { throw IOException("Listing denied") },
                remove = { error("Cannot assume folder is empty") },
            )
            throw AssertionError("Listing must fail")
        } catch (_: IOException) {
            // An unreadable folder is never assumed empty.
        }
    }

    @Test
    fun removalUsesTheChosenActionForFilesAndFolders() {
        val folder = file("share/album", true)
        val image = file("share/image.jpg")
        for (requested in listOf(FileRemovalAction.Trash, FileRemovalAction.Delete)) {
            assertEquals(requested, removalActionFor(folder, requested))
            assertEquals(requested, removalActionFor(image, requested))
            assertTrue(canRemoveFile(folder, requested))
            assertTrue(canRemoveFile(image, requested))
        }
        assertEquals(FileRemovalAction.EmptyTrash, removalActionFor(file("share/.trash", true), FileRemovalAction.EmptyTrash))
        assertFalse(canRemoveFile(image, FileRemovalAction.EmptyTrash))
    }

    @Test
    fun galleryImagesAndArchivesAlwaysMoveAndDoNotOpenTheDeletePanel() {
        val image = file("share/image.jpg").copy(galleryTrash = true)
        val archive = file("share/book.zip").copy(galleryTrash = true)
        for (item in listOf(image, archive)) {
            assertEquals(FileRemovalAction.Trash, removalActionFor(item, FileRemovalAction.Delete))
            assertTrue(canRemoveFile(item, FileRemovalAction.Delete))
            assertFalse(removalRequiresPanel(listOf(item), FileRemovalAction.Trash))
        }
        assertTrue(removalRequiresPanel(listOf(file("share/album", true), image), FileRemovalAction.Trash))
        assertTrue(removalRequiresPanel(listOf(image, file("share/file.txt")), FileRemovalAction.Delete))
        assertTrue(removalRequiresPanel(listOf(file("share/.trash", true)), FileRemovalAction.EmptyTrash))
        val normalized = trashSelectionRoots(listOf(image)).single()
        assertTrue(normalized.galleryTrash)
    }

    @Test
    fun folderDeletionCollectsChildrenBeforeParentsWithoutDeletingAnything() = runBlocking {
        val album = file("share/album", true)
        val sub = file("share/album/sub", true)
        val calls = mutableListOf<String>()
        val result = collectRemovalFiles(listOf(album), FileRemovalAction.Delete, children = { location ->
            calls += location.path
            when (location.path) {
                album.source.path -> listOf(sub, file("share/album/cover.jpg"))
                sub.source.path -> listOf(file("share/album/sub/page.jpg"))
                else -> error("Unexpected enumeration")
            }
        })
        assertEquals(listOf("share/album", "share/album/sub"), calls)
        assertEquals(listOf("share/album/sub/page.jpg", "share/album/sub", "share/album/cover.jpg", "share/album"), result.map { it.source.path })
        assertTrue(removalRequiresPanel(listOf(album), FileRemovalAction.Delete))
        assertTrue(removalRequiresPanel(listOf(album), FileRemovalAction.Trash))
        assertFalse(removalRequiresPanel(listOf(file("share/a.jpg")), FileRemovalAction.Delete))
    }

    @Test
    fun movingFoldersToTrashDoesNotExpandTheirContents() = runBlocking {
        val album = file("share/album", true)
        val result = collectRemovalFiles(listOf(album), FileRemovalAction.Trash, children = { error("Must move whole folder") })
        assertEquals(listOf(album), result)
    }

    @Test
    fun recursiveCollectionRejectsProtectedTrashAndCyclesBeforeStart() = runBlocking {
        val album = file("share/album", true)
        for (child in listOf(file("share/album/.trash", true), album)) {
            try {
                collectRemovalFiles(listOf(album), FileRemovalAction.Delete, children = { listOf(child) })
                throw AssertionError("Accepted unsafe recursive deletion")
            } catch (_: IllegalArgumentException) {
                // Protected .trash is rejected before the panel can enable Start.
            } catch (_: IllegalStateException) {
                // Repeated directory identity is a cycle.
            }
        }
    }

    @Test
    fun cancelledCollectionNeverReturnsAPartialDeletionPlan() = runBlocking {
        try {
            collectRemovalFiles(listOf(file("share/album", true)), FileRemovalAction.Delete, children = { throw CancellationException("Stopped") })
            throw AssertionError("Returned a partial plan")
        } catch (_: CancellationException) {
            // Start stays disabled when preparation is cancelled.
        }
    }

    @Test
    fun deletionOnlyAcceptsSafDocumentsForLocalSources() {
        fun local(path: String) = TransferLocation(ExplorerWindows.Kind.Local, 0, path)
        for (path in listOf("mediastore:/external/images/a.jpg", "/storage/emulated/0/Pictures/a.jpg", "content:/media/external/images/media/1")) {
            assertFalse(canRemove(local(path), FileRemovalAction.Delete))
            assertFalse(canTrash(local(path)))
        }
        assertTrue(canRemove(local("content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2Fa.jpg"), FileRemovalAction.Delete))
    }

    @Test
    fun trashOnlyAcceptsPhysicalFolders() {
        val source = file("share/album", true).source
        assertTrue(canTrashFolder(source, true))
        assertFalse(canTrashFolder(source, false))
        assertFalse(canTrashFolder(source.copy(path = "share/album.zip"), true))
        assertFalse(canTrashFolder(source.copy(path = "share/album.cbz"), true))
        assertFalse(canTrashFolder(source.copy(path = "share/.trash"), true))
    }

    @Test
    fun permanentDeletionRejectsArchiveMembersAndProtectsTrashContents() {
        val physical = file("share/album/a.jpg").source
        assertTrue(canRemove(physical, FileRemovalAction.Delete))
        assertFalse(canRemove(physical.copy(path = "share/book.zip/a.jpg"), FileRemovalAction.Delete))
        assertFalse(canRemove(physical.copy(path = "share/.trash/a.jpg"), FileRemovalAction.Delete))
        assertFalse(canRemove(physical.copy(path = "share/../a.jpg"), FileRemovalAction.Delete))
        assertFalse(canRemove(physical.copy(path = ""), FileRemovalAction.Delete))
    }

    @Test
    fun emptyTrashOnlyAcceptsThePhysicalTrashFolder() {
        val source = file("share/.trash", true).source
        assertTrue(canRemove(source, FileRemovalAction.EmptyTrash))
        assertFalse(canRemove(source.copy(path = "share/.trash/2026-11-01"), FileRemovalAction.EmptyTrash))
        assertFalse(canRemove(source.copy(path = "share/not.trash"), FileRemovalAction.EmptyTrash))
        assertFalse(canRemove(source.copy(path = "share/book.zip/.trash"), FileRemovalAction.EmptyTrash))
        val saf = TransferLocation(
            ExplorerWindows.Kind.Local,
            0,
            "content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2F.trash",
        )
        assertTrue(canRemove(saf, FileRemovalAction.EmptyTrash))
        assertFalse(canRemove(saf.copy(path = "content:/provider/tree/primary%3A.trash/document/primary%3A.trash"), FileRemovalAction.EmptyTrash))
    }

    @Test
    fun safDeleteOnlyAcceptsTreeDocumentsOutsideTrash() {
        fun local(path: String) = TransferLocation(ExplorerWindows.Kind.Local, 0, path)
        val uri = "content:/provider/tree/primary%3APhotos/document/primary%3APhotos/album"
        assertTrue(canTrash(local(uri)))
        assertFalse(canTrash(local("/storage/emulated/0/Photos/a.jpg")))
        assertFalse(canTrash(local("mediastore:/Photos/a.jpg")))
        assertFalse(canTrash(local("content:/provider/document/123")))
        assertFalse(canTrash(local("content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2F.trash%2Fa.jpg")))
        assertTrue(isInsideTrashPath("content:/provider/tree/primary%3APhotos%2F.trash/document/primary%3APhotos%2F.trash/a.jpg"))
        assertTrue(isInsideTrashPath("content:/provider/tree/primary%3A/document/primary%3A.trash/a 100%.jpg"))
        val root = trashSelectionRoots(listOf(TransferFile(local(uri), "album", true, 0, 0))).single()
        assertEquals(uri, root.source.path)
    }

    @Test
    fun safParentMustStayInsideGrantedTreeAndNeverMoveTheRoot() {
        assertEquals("primary:", safTrashParentId("primary:", "primary:Photos"))
        assertEquals("primary:Photos", safTrashParentId("primary:", "primary:Photos/album"))
        assertEquals("primary:Photos", safTrashParentId("primary:Photos", "primary:Photos/album"))
        assertEquals("primary:Photos/album", safTrashParentId("primary:Photos", "primary:Photos/album/a.jpg"))
        for (id in listOf("primary:Photos", "primary:Other/a.jpg", "primary:Photos/../Other/a.jpg", "opaque-id")) {
            try {
                safTrashParentId("primary:Photos", id)
                throw AssertionError("Accepted an ungranted SAF parent: $id")
            } catch (_: UnsupportedOperationException) {
                // No provider mutation is allowed for the selected tree or unknown parent.
            }
        }
    }

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
