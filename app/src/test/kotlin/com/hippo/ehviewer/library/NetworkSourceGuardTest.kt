package com.hippo.ehviewer.library

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSourceGuardTest {
    private fun source(path: String, kind: ExplorerWindows.Kind = ExplorerWindows.Kind.Smb, id: Long = 1) = TransferLocation(kind, id, path)

    @Test
    fun safVolumeRootListingCanRestoreTopLevelFolder() {
        val guard = NetworkSourceGuard()
        val root = TransferLocation(ExplorerWindows.Kind.Local, 0, "content:/provider/tree/primary%3A/document/primary%3A")
        val folder = root.copy(path = "content:/provider/tree/primary%3A/document/primary%3APhotos")
        val deletion = guard.acquire(listOf(folder), deleting = true)!!
        deletion.attempted(folder)
        deletion.close()
        assertNull(guard.acquire(listOf(folder), deleting = false))
        guard.listed(root, listOf("Photos"), guard.listingStarted())
        assertNotNull(guard.acquire(listOf(folder), deleting = false))
    }

    @Test
    fun safAliasesShareDeleteOwnershipAndOnlyFreshListingsRestoreThem() {
        val guard = NetworkSourceGuard()
        fun local(path: String) = TransferLocation(ExplorerWindows.Kind.Local, 0, path)
        val folder = local("content:/provider/tree/primary%3APhotos/document/primary%3APhotos/album")
        val alias = local("content:/provider/tree/primary%3A/document/primary%3APhotos%2Falbum")
        val oldListing = guard.listingStarted()
        val deletion = guard.acquire(listOf(folder), deleting = true)!!
        assertNull(guard.acquire(listOf(alias), deleting = true))
        assertNull(guard.acquire(listOf(alias.child("a.jpg")), deleting = false))
        deletion.attempted(folder)
        deletion.close()
        val parent = local("content:/provider/tree/primary%3APhotos/document/primary%3APhotos")
        guard.listed(parent, listOf("album"), oldListing)
        assertNull(guard.acquire(listOf(alias), deleting = false))
        guard.listed(parent, listOf("album"), guard.listingStarted())
        assertNotNull(guard.acquire(listOf(alias), deleting = false))
    }

    @Test
    fun pendingDeleteBlocksDuplicateDeleteUploadAndDescendantsForBothProtocols() {
        for (kind in listOf(ExplorerWindows.Kind.Smb, ExplorerWindows.Kind.WebDav)) {
            val guard = NetworkSourceGuard()
            val folder = source("photos/album", kind)
            val lease = guard.acquire(listOf(folder), deleting = true)!!
            for (deleting in listOf(true, false)) {
                assertNull(guard.acquire(listOf(folder), deleting))
                assertNull(guard.acquire(listOf(folder.child("photo.jpg")), deleting))
                assertNull(guard.acquire(listOf(source("photos", kind)), deleting))
            }
            assertNotNull(guard.acquire(listOf(source("photos/album2", kind)), deleting = true))
            assertNotNull(guard.acquire(listOf(source(folder.path, kind, 2)), deleting = true))
            lease.close()
            // A queued delete cancelled without attempting a move releases the source.
            assertNotNull(guard.acquire(listOf(folder), deleting = false))
        }
    }

    @Test
    fun completedOrUncertainMoveStaysBlockedAfterJobIsDismissedUntilFreshlyConfirmed() {
        val guard = NetworkSourceGuard()
        val folder = source("photos/album")
        val staleListing = guard.listingStarted()
        val lease = guard.acquire(listOf(folder), deleting = true)!!
        lease.attempted(folder)
        lease.close()
        lease.close()
        assertNull(guard.acquire(listOf(folder), deleting = true))
        assertNull(guard.acquire(listOf(folder.child("photo.jpg")), deleting = false))
        guard.listed(source("photos"), listOf("album"), staleListing)
        assertNull(guard.acquire(listOf(folder), deleting = false))
        val freshListing = guard.listingStarted()
        guard.listed(source("photos"), emptyList(), freshListing)
        assertNull(guard.acquire(listOf(folder), deleting = true))
        // Permission denial, restoration, or recreation can be confirmed by a later real list.
        guard.listed(source("photos"), listOf("album"), freshListing)
        assertNotNull(guard.acquire(listOf(folder), deleting = false))
    }

    @Test
    fun preparedUploadIsRecheckedWhenStartedAfterItsSourceWasDeleted() {
        val guard = NetworkSourceGuard()
        val folder = source("photos/album")
        val prepare = guard.acquire(listOf(folder), deleting = false)!!
        assertNull(guard.acquire(listOf(folder), deleting = true))
        prepare.close()
        val delete = guard.acquire(listOf(folder), deleting = true)!!
        delete.attempted(folder)
        delete.close()
        assertNull(guard.acquire(listOf(folder, folder.child("photo.jpg")), deleting = false))
    }

    @Test
    fun concurrentUploadsShareSourcesButDeleteWaitsForEveryReader() {
        val guard = NetworkSourceGuard()
        val file = source("photos/a.jpg")
        val first = guard.acquire(listOf(file), deleting = false)!!
        val second = guard.acquire(listOf(file), deleting = false)!!
        first.close()
        assertNull(guard.acquire(listOf(source("photos")), deleting = true))
        second.close()
        assertNotNull(guard.acquire(listOf(source("photos")), deleting = true))
    }

    @Test
    fun overlappingDeleteBatchAcquisitionIsAtomic() {
        val guard = NetworkSourceGuard()
        val first = source("photos/a")
        val second = source("photos/b")
        val lease = guard.acquire(listOf(second), deleting = true)!!
        assertNull(guard.acquire(listOf(first, second), deleting = true))
        assertNotNull(guard.acquire(listOf(first), deleting = false))
        lease.close()
    }

    @Test
    fun simultaneousDeleteClicksAcquireOnlyOneLease() {
        val guard = NetworkSourceGuard()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                pool.submit<NetworkSourceGuard.Lease?> {
                    check(start.await(3, TimeUnit.SECONDS))
                    guard.acquire(listOf(source("photos/album")), deleting = true)
                }
            }
            start.countDown()
            val leases = futures.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, leases.count { it != null })
            leases.forEach { it?.close() }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun olderWorkerCompletionCannotReleaseNewerUploadLease() {
        val guard = NetworkSourceGuard()
        val folder = source("photos/album")
        val preparation = guard.acquire(listOf(folder), deleting = false)!!
        val transfer = guard.acquire(listOf(folder), deleting = false)!!
        preparation.close()
        preparation.close()
        assertNull(guard.acquire(listOf(folder), deleting = true))
        transfer.close()
        assertNotNull(guard.acquire(listOf(folder), deleting = true))
    }
}
