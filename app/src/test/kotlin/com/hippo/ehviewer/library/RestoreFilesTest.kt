package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreFilesTest {
    @Test
    fun restoreRemovesOnlyTrashContainerAndDateAndKeepsRelativePath() {
        assertEquals("photos/album/sub/a.jpg", trashRestorePath("photos/.trash/2026-11-07/album/sub/a.jpg"))
        assertEquals("photos/a.jpg", trashRestorePath("photos/.TRASH/2026-11-07/a.jpg"))
        assertEquals("a.jpg", trashRestorePath(".trash/2026-11-07/a.jpg"))
        assertEquals("primary:a.jpg", trashRestorePath("primary:.trash/2026-11-07/a.jpg"))
        assertEquals("primary:Photos/a.jpg", trashRestorePath("primary:Photos/.trash/2026-11-07/a.jpg"))
        assertEquals("photos/album/a.jpg", trashRestorePath("photos\\.trash\\2026-11-07\\album\\a.jpg"))
    }

    @Test
    fun trashInfrastructureAndMalformedPathsCannotBeRestored() {
        for (path in listOf("photos/.trash", "photos/.trash/2026-11-07", "photos/.trash/not-a-date/a.jpg", "photos/.trash/2026-11-07/../a.jpg", "photos/.trash/2026-11-07//a.jpg", "photos/.trash/2026-11-07/album/.trash/a.jpg", "photos/a.jpg")) {
            assertNull(path, trashRestorePath(path))
        }
    }

    @Test
    fun safRestoreStaysInsideGrantedTree() {
        assertEquals("primary:Photos/album/a.jpg", safRestorePath("primary:Photos", "primary:Photos/.trash/2026-11-07/album/a.jpg"))
        assertEquals("primary:a.jpg", safRestorePath("primary:", "primary:.trash/2026-11-07/a.jpg"))
        assertNull(safRestorePath("primary:Photos/.trash", "primary:Photos/.trash/2026-11-07/a.jpg"))
        assertNull(safRestorePath("primary:Other", "primary:Photos/.trash/2026-11-07/a.jpg"))
    }

    @Test
    fun safRestorePreservesEncodedSpacesPlusAndPercent() {
        val source = TransferLocation(ExplorerWindows.Kind.Local, 0, "content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2F.trash%2F2026-11-07%2Fa%2Bb%20100%25.jpg")
        assertTrue(canRestore(source))
        assertEquals("content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2Fa%2Bb%20100%25.jpg", restoreLocation(source)?.path)
        assertTrue(canRemove(source, FileRemovalAction.Restore))
        assertFalse(canRemove(source, FileRemovalAction.Delete))
    }

    @Test
    fun zipContentsAreNotIndividuallyRestorable() {
        val source = TransferLocation(ExplorerWindows.Kind.Smb, 1, "photos/.trash/2026-11-07/book.zip/page.jpg")
        assertFalse(canRestore(source))
        assertTrue(canRestore(source.copy(path = "photos/.trash/2026-11-07/book.zip")))
    }

    @Test
    fun localZipFolderRowsRetainMemberIdentityForWarnings() {
        val zip = "content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2Fbook.zip"
        assertEquals(zip, localFolderRemovalPath(zip, "book.zip", null, false, true))
        assertEquals("zipfile:$zip!chapter", localFolderRemovalPath(zip, "book.zip/chapter", null, true, true))
        assertEquals("zipfile:$zip!chapter/sub", localFolderRemovalPath(zip, "sub", "chapter", false, true))
        assertEquals("zipfile:$zip!chapter/sub", localFolderRemovalPath(zip, "chapter/sub", "chapter", true, true))
        assertEquals("zipfile:$zip!.", localFolderRemovalPath(zip, "", "", true, true))
    }

    @Test
    fun zipMemberRemovalUsesOnePhysicalArchiveAndForcesTrash() {
        val source = TransferLocation(ExplorerWindows.Kind.Smb, 1, "photos/book.zip/page.jpg")
        val member = TransferFile(source, "page.jpg", false, 0, 0)
        val targets = trashSelectionRoots(removalTargets(listOf(member, member.copy(source = source.copy(path = "photos/book.zip/chapter/next.jpg")))))
        assertEquals(1, targets.size)
        assertEquals("photos/book.zip", targets.single().source.path)
        assertEquals(FileRemovalAction.Trash, removalActionFor(targets.single(), FileRemovalAction.Delete))
        assertTrue(canRemoveItem(source, FileRemovalAction.Delete))
        assertEquals("photos/book.zip", zipRemovalArchive(source.copy(path = "photos/book.zip/."))?.path)
        val local = source.copy(kind = ExplorerWindows.Kind.Local, path = "zipfile:content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2Fbook.zip!chapter/page.jpg")
        assertEquals("content:/provider/tree/primary%3APhotos/document/primary%3APhotos%2Fbook.zip", zipRemovalArchive(local)?.path)
    }

    @Test
    fun explicitlySelectedArchiveKeepsRequestedDeleteEvenWithSelectedMembers() {
        val source = TransferLocation(ExplorerWindows.Kind.Smb, 1, "photos/book.zip")
        val archive = TransferFile(source, "book.zip", false, 0, 0)
        val member = archive.copy(source = source.copy(path = "photos/book.zip/page.jpg"))
        for (items in listOf(listOf(member, archive), listOf(archive, member))) {
            val target = trashSelectionRoots(removalTargets(items)).single()
            assertEquals(source, target.source)
            assertEquals(FileRemovalAction.Delete, removalActionFor(target, FileRemovalAction.Delete))
        }
    }
}
