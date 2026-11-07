package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseMutationRefreshTest {
    private fun folder(path: String, kind: ExplorerWindows.Kind = ExplorerWindows.Kind.Smb, id: Long = 1) = TransferLocation(kind, id, path)

    @Test
    fun onlyExistingChangedChildrenAreRescannedEvenWithUnchangedMtime() {
        val changes = BrowseMutationRefresh()
        changes.changed(listOf(folder("photos/album"), folder("photos/deleted")))
        val live = listOf(
            RemoteChild("album", true, lastModifiedMs = 100),
            RemoteChild("unrelated", true, lastModifiedMs = 100),
            RemoteChild("deleted", false),
        )
        val snapshot = changes.snapshot(folder("photos"))
        assertTrue(snapshot.pending)
        assertFalse(snapshot.currentChanged)
        assertEquals(listOf("album"), snapshot.changedChildren(live).map { it.name })
        assertTrue(changes.snapshot(folder("photos/album")).currentChanged)
        assertFalse(changes.snapshot(folder("photos/album2")).pending)
        assertFalse(changes.snapshot(folder("photos", id = 2)).pending)
        assertFalse(changes.snapshot(folder("photos", ExplorerWindows.Kind.WebDav)).pending)
    }

    @Test
    fun refreshingParentDoesNotHideChangesFromCachedChildOrAnotherAncestor() {
        val changes = BrowseMutationRefresh()
        changes.changed(listOf(folder("photos/album")))
        changes.complete(changes.snapshot(folder("photos")))
        assertFalse(changes.snapshot(folder("photos")).pending)
        assertTrue(changes.snapshot(folder("photos/album")).pending)
        assertTrue(changes.snapshot(folder("")).pending)
        changes.complete(changes.snapshot(folder("photos/album")))
        assertFalse(changes.snapshot(folder("photos/album")).pending)
        assertTrue(changes.snapshot(folder("")).pending)
    }

    @Test
    fun failedScansAndNewerMutationsKeepTheirRefreshHints() {
        val changes = BrowseMutationRefresh()
        changes.changed(listOf(folder("photos/album")))
        val old = changes.snapshot(folder("photos"))
        assertTrue(changes.snapshot(folder("photos")).pending)
        changes.changed(listOf(folder("photos/album"), folder("photos/other")))
        changes.complete(old)
        val latest = changes.snapshot(folder("photos"))
        assertEquals(setOf("album", "other"), latest.childNames)
        changes.complete(latest)
        changes.complete(old)
        assertFalse(changes.snapshot(folder("photos")).pending)
    }

    @Test
    fun rootAndNestedChangesKeepCurrentScanAndChildScanSeparate() {
        val changes = BrowseMutationRefresh()
        changes.changed(listOf(folder(""), folder("album/sub")))
        val root = changes.snapshot(folder(""))
        assertTrue(root.currentChanged)
        assertEquals(setOf("album"), root.childNames)
        assertEquals(setOf("sub"), changes.snapshot(folder("album")).childNames)
    }

    @Test
    fun safGrantAliasesAndMediaStorePathsShareMutationIdentity() {
        val changes = BrowseMutationRefresh()
        val source = folder("content:/com.android.externalstorage.documents/tree/primary%3APhotos/document/primary%3APhotos%2FAlbum%20%2B", ExplorerWindows.Kind.Local, 0)
        changes.changed(listOf(source))
        val parent = folder("mediastore:/Photos", ExplorerWindows.Kind.Local, 0)
        assertEquals(setOf("Album +"), changes.snapshot(parent).childNames)
        val alias = source.copy(path = "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3APhotos/Album%20%2B")
        assertTrue(changes.snapshot(alias).currentChanged)
        changes.complete(changes.snapshot(alias))
        assertFalse(changes.snapshot(source).pending)
        assertTrue(changes.snapshot(parent).pending)
    }

    @Test
    fun removableVolumeAliasesNeverMatchInternalStorage() {
        val changes = BrowseMutationRefresh()
        changes.changed(listOf(folder("content:/com.android.externalstorage.documents/tree/ABCD-1234%3A/document/ABCD-1234%3APhotos", ExplorerWindows.Kind.Local, 0)))
        assertTrue(changes.snapshot(folder("mediastore:/@volume-abcd-1234/Photos", ExplorerWindows.Kind.Local, 0)).currentChanged)
        assertFalse(changes.snapshot(folder("mediastore:/Photos", ExplorerWindows.Kind.Local, 0)).pending)
    }

    @Test
    fun encodedSafRemovalParentPreservesDocumentIdentity() {
        val source = folder("content:/com.android.externalstorage.documents/tree/primary%3A/document/primary%3APhotos%2FAlbum%20%2B%2Fa.jpg", ExplorerWindows.Kind.Local, 0)
        assertEquals("content:/com.android.externalstorage.documents/tree/primary%3A/document/primary%3APhotos%2FAlbum%20%2B", removalParent(source).path)
        val top = source.copy(path = "content:/com.android.externalstorage.documents/tree/primary%3A/document/primary%3Aa.jpg")
        assertEquals("content:/com.android.externalstorage.documents/tree/primary%3A/document/primary%3A", removalParent(top).path)
    }
}
