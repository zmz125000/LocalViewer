package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadDestinationCheckTest {
    private fun location(path: String, kind: ExplorerWindows.Kind = ExplorerWindows.Kind.Smb, id: Long = 1) = TransferLocation(kind, id, path)
    private fun file(path: String, name: String = path.substringAfterLast('/'), directory: Boolean = false) = TransferFile(location(path), name, directory, 0, 0)
    private fun identity(location: TransferLocation) = UploadPathIdentity(location.kind, location.sourceId.toString(), location.path.trim('/'))

    @Test
    fun fileUploadToItsOwnParentIsRejectedBeforeConflictHandling() = runBlocking {
        assertTrue(uploadDestinationOverlapsSource(listOf(file("photos/a.jpg")), location("photos"), ::identity))
        assertFalse(uploadDestinationOverlapsSource(listOf(file("photos/a.jpg")), location("other"), ::identity))
    }

    @Test
    fun folderUploadToItsOwnParentSelfOrDescendantIsRejected() = runBlocking {
        val folder = file("photos/album", directory = true)
        for (target in listOf("photos", "photos/album", "photos/album/sub")) {
            assertTrue(target, uploadDestinationOverlapsSource(listOf(folder), location(target), ::identity))
        }
        assertFalse(uploadDestinationOverlapsSource(listOf(folder), location("photos/album2"), ::identity))
        assertFalse(uploadDestinationOverlapsSource(listOf(folder), location("backup"), ::identity))
    }

    @Test
    fun oneOverlappingItemRejectsTheWholeBatch() = runBlocking {
        val roots = listOf(file("elsewhere/b.jpg"), file("photos/a.jpg"))
        assertTrue(uploadDestinationOverlapsSource(roots, location("photos"), ::identity))
    }

    @Test
    fun rootFileAndRenamedRootUseTheActualOutputName() = runBlocking {
        assertTrue(uploadDestinationOverlapsSource(listOf(file("a.jpg")), location(""), ::identity))
        assertFalse(uploadDestinationOverlapsSource(listOf(file("photos/a.jpg", "different.jpg")), location("photos"), ::identity))
    }

    @Test
    fun sameRelativePathOnDifferentSourcesIsAllowed() = runBlocking {
        assertFalse(uploadDestinationOverlapsSource(listOf(file("photos/a.jpg")), location("photos", id = 2), ::identity))
    }

    @Test
    fun safGrantAliasesIgnoreLocalRootIdsAndEncoding() = runBlocking {
        val source = location("content:/com.android.externalstorage.documents/tree/primary%3A/document/primary%3APhotos%2Fa.jpg", ExplorerWindows.Kind.Local, 0)
        val destination = location("content:/com.android.externalstorage.documents/tree/primary%3APhotos/document/primary%3APhotos", ExplorerWindows.Kind.Local, 7)
        fun localIdentity(path: TransferLocation) = UploadPathIdentity(path.kind, "local", BrowseMutationRefresh.canonical(path).path)
        assertTrue(uploadDestinationOverlapsSource(listOf(TransferFile(source, "a.jpg", false, 0, 0)), destination, ::localIdentity))
    }

    @Test
    fun smbAliasesWithDifferentSharePrefixesAndCaseStillOverlap() = runBlocking {
        val sources = mapOf(
            1L to SmbSourceEntity(id = 1, displayName = "One", host = "NAS", share = "Share", pathPrefix = "Photos", addedAt = 0),
            2L to SmbSourceEntity(id = 2, displayName = "Two", host = "nas", share = "", addedAt = 0),
        )
        assertTrue(
            uploadDestinationOverlapsSource(listOf(file("a.jpg")), location("share/photos", id = 2)) {
                smbUploadIdentity(sources.getValue(it.sourceId), it.path)
            },
        )
    }

    @Test
    fun webDavAliasesShareIdentityWhilePathCaseStaysSignificant() = runBlocking {
        val sources = mapOf(
            1L to WebDavSourceEntity(id = 1, displayName = "One", baseUrl = "https://NAS/dav/", pathPrefix = "Photos", addedAt = 0),
            2L to WebDavSourceEntity(id = 2, displayName = "Two", baseUrl = "https://nas:443/dav/Photos/", addedAt = 0),
        )
        val root = TransferFile(location("a.jpg", ExplorerWindows.Kind.WebDav), "a.jpg", false, 0, 0)
        suspend fun overlaps(name: String) = uploadDestinationOverlapsSource(listOf(root.copy(relative = name)), location("", ExplorerWindows.Kind.WebDav, 2)) {
            webDavUploadIdentity(sources.getValue(it.sourceId), it.path)
        }
        assertTrue(overlaps("a.jpg"))
        assertFalse(overlaps("A.jpg"))
    }
}
