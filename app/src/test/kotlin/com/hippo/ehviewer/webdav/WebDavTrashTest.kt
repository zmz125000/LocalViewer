package com.hippo.ehviewer.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebDavTrashTest {
    @Test
    fun restoreRecreatesRelativeParentsAndRenamesConflictsWithoutOverwrite() = runBlocking {
        val operations = mutableListOf<WebDavTrashRequest>()
        var moves = 0
        val result = restoreWebDavFromTrash("photos/.trash/2026-11-07/album/sub/photo.jpg", false, {}) {
            operations += it
            when (it.method) {
                "MKCOL" -> WebDavTrashResponse(201)
                "MOVE" -> WebDavTrashResponse(if (++moves == 1) 412 else 201)
                else -> error("Unexpected request")
            }
        }
        assertEquals("photos/album/sub/photo (2).jpg", result)
        assertEquals(listOf("photos", "photos/album", "photos/album/sub"), operations.filter { it.method == "MKCOL" }.map { it.path })
        assertEquals("photos/album/sub/photo (2).jpg", operations.last().destination)
        assertTrue(operations.filter { it.method == "MOVE" }.all { it.overwrite == "F" && it.path == "photos/.trash/2026-11-07/album/sub/photo.jpg" })
    }

    @Test
    fun rootRestoreDoesNotCreateAnEmptyDirectoryOrAbsoluteDestination() = runBlocking {
        val operations = mutableListOf<WebDavTrashRequest>()
        val result = restoreWebDavFromTrash(".trash/2026-11-07/book.zip", false, {}) {
            operations += it
            WebDavTrashResponse(201)
        }
        assertEquals("book.zip", result)
        assertEquals(1, operations.size)
        assertEquals("book.zip", operations.single().destination)
    }

    @Test
    fun restoreMovesWholeFolderAndKeepsDotsWhenRenamingConflicts() = runBlocking {
        var moves = 0
        val result = restoreWebDavFromTrash("photos/.trash/2026-11-07/album.v1", true, {}) {
            if (it.method != "MOVE") return@restoreWebDavFromTrash WebDavTrashResponse(201)
            assertEquals("infinity", it.depth)
            assertEquals("F", it.overwrite)
            WebDavTrashResponse(if (++moves == 1) 412 else 201)
        }
        assertEquals("photos/album.v1 (2)", result)
    }

    @Test
    fun failedRestoreNeverRetriesOrDeletesSource() = runBlocking {
        for (status in listOf(403, 207, 500)) {
            val operations = mutableListOf<WebDavTrashRequest>()
            try {
                restoreWebDavFromTrash("photos/.trash/2026-11-07/a.jpg", false, {}) {
                    operations += it
                    WebDavTrashResponse(if (it.method == "MOVE") status else 201)
                }
                fail("Restore must fail")
            } catch (_: WebDavTrashException) {
                assertEquals(1, operations.count { it.method == "MOVE" })
                assertTrue(operations.none { it.method == "DELETE" || it.method == "PUT" })
            }
        }
    }

    @Test
    fun unsupportedRestoreMoveRejectsWithoutPayloadFallbackOrRetry() = runBlocking {
        for (directory in listOf(false, true)) {
            for (status in listOf(405, 501)) {
                val operations = mutableListOf<WebDavTrashRequest>()
                try {
                    restoreWebDavFromTrash(".trash/2026-11-07/album", directory, {}) {
                        operations += it
                        WebDavTrashResponse(status)
                    }
                    fail("Unsupported MOVE must be rejected")
                } catch (_: UnsupportedOperationException) {
                    assertEquals(listOf("MOVE"), operations.map { it.method })
                    assertEquals("F", operations.single().overwrite)
                    assertEquals("album", operations.single().destination)
                }
            }
        }
    }

    @Test
    fun restoreRejectsInfrastructureBeforeContactingServer() = runBlocking {
        for (path in listOf(".trash", ".trash/2026-11-07", "photos/a.jpg")) {
            try {
                restoreWebDavFromTrash(path, false, {}) { error("Must not contact server") }
                fail("Invalid restoration target")
            } catch (_: IllegalArgumentException) {
                // No server request before validating the restore path.
            }
        }
    }

    @Test
    fun protectedTrashItemsAreRejectedBeforeAnyServerRequest() = runBlocking {
        for (path in listOf(".trash", "photos/.trash/2026-10-30/photo.jpg", "photos/.TRASH/album")) {
            var requests = 0
            try {
                moveWebDavToTrash(path, false, "2026-10-30", {}) {
                    requests++
                    WebDavTrashResponse(201)
                }
                fail("Trash contents must be protected")
            } catch (_: com.hippo.ehviewer.library.TrashProtectedException) {
                assertEquals(0, requests)
            }
        }
    }

    @Test
    fun folderUsesOneExclusiveServerMoveWithoutListingItsChildren() = runBlocking {
        val operations = mutableListOf<WebDavTrashRequest>()
        val result = moveWebDavToTrash("photos/album.v1", true, "2026-10-30", {}) {
            operations += it
            WebDavTrashResponse(201)
        }
        assertEquals("album.v1", result)
        assertEquals(listOf("MKCOL", "MKCOL", "MOVE"), operations.map { it.method })
        assertEquals(listOf("photos/.trash", "photos/.trash/2026-10-30", "photos/album.v1"), operations.map { it.path })
        assertEquals("photos/.trash/2026-10-30/album.v1", operations.last().destination)
        assertEquals("F", operations.last().overwrite)
        assertEquals("infinity", operations.last().depth)
        assertTrue(operations.last().directory)
    }

    @Test
    fun existingTrashAndDateFoldersAreVerifiedWithDepthZeroAndReused() = runBlocking {
        val operations = mutableListOf<WebDavTrashRequest>()
        moveWebDavToTrash("photo.jpg", false, "2026-10-30", {}) {
            operations += it
            when (it.method) {
                "MKCOL" -> WebDavTrashResponse(405)
                "PROPFIND" -> WebDavTrashResponse(207, collection = true)
                "MOVE" -> WebDavTrashResponse(204)
                else -> error("Unexpected payload operation")
            }
        }
        assertEquals(listOf("MKCOL", "PROPFIND", "MKCOL", "PROPFIND", "MOVE"), operations.map { it.method })
        assertTrue(operations.filter { it.method == "PROPFIND" }.all { it.depth == "0" })
        assertEquals(".trash/2026-10-30/photo.jpg", operations.last().destination)
    }

    @Test
    fun nameConflictsRetryOnlyTheDestinationWithOverwriteDisabled() = runBlocking {
        for (directory in listOf(false, true)) {
            val moves = mutableListOf<WebDavTrashRequest>()
            val result = moveWebDavToTrash("photos/album.v1", directory, "2026-10-30", {}) {
                if (it.method != "MOVE") return@moveWebDavToTrash WebDavTrashResponse(201)
                moves += it
                WebDavTrashResponse(if (moves.size <= 3) 412 else 201)
            }
            val name = if (directory) "album.v1 (4)" else "album (4).v1"
            assertEquals(name, result)
            assertEquals(4, moves.size)
            assertEquals("photos/.trash/2026-10-30/$name", moves.last().destination)
            assertTrue(moves.all { it.overwrite == "F" && it.path == "photos/album.v1" })
        }
    }

    @Test
    fun deniedAndPartialMovesAreFailuresWithoutFallbackOrRetry() = runBlocking {
        for (result in listOf(WebDavTrashResponse(403), WebDavTrashResponse(207), WebDavTrashResponse(207, failureStatus = 403))) {
            val operations = mutableListOf<String>()
            try {
                moveWebDavToTrash("photo.jpg", false, "2026-10-30", {}) {
                    operations += it.method
                    if (it.method == "MOVE") result else WebDavTrashResponse(201)
                }
                fail("A denied or partial MOVE must not succeed")
            } catch (error: WebDavTrashException) {
                assertEquals(result.failureStatus ?: result.status, error.status)
            }
            assertEquals(listOf("MKCOL", "MKCOL", "MOVE"), operations)
        }
    }

    @Test
    fun unsupportedMoveAndPermissionDeniedDuringMkdirStopImmediately() = runBlocking {
        for (status in listOf(401, 403, 405, 501)) {
            var calls = 0
            try {
                moveWebDavToTrash("photo.jpg", false, "2026-10-30", {}) {
                    calls++
                    when (it.method) {
                        "MOVE" -> WebDavTrashResponse(status)
                        "MKCOL" -> if (status == 401 || status == 403) WebDavTrashResponse(status) else WebDavTrashResponse(201)
                        else -> error("Unexpected request")
                    }
                }
                fail("Operation should fail")
            } catch (error: Exception) {
                if (status == 405 || status == 501) {
                    assertTrue(error is UnsupportedOperationException)
                } else {
                    assertEquals(status, (error as WebDavTrashException).status)
                }
            }
            assertEquals(if (status == 401 || status == 403) 1 else 3, calls)
        }
    }

    @Test
    fun existingFileCannotBeUsedAsTheTrashCollection() = runBlocking {
        val operations = mutableListOf<String>()
        try {
            moveWebDavToTrash("photo.jpg", false, "2026-10-30", {}) {
                operations += it.method
                WebDavTrashResponse(if (it.method == "MKCOL") 405 else 207, collection = false)
            }
            fail("A file is not a trash collection")
        } catch (_: WebDavTrashException) {
            assertEquals(listOf("MKCOL", "PROPFIND"), operations)
        }
    }

    @Test
    fun lostMoveReplyAndCancellationNeverReplayTheMove() = runBlocking {
        for (failure in listOf(IOException("Reply lost after commit"), CancellationException("Stopped"))) {
            var moves = 0
            try {
                moveWebDavToTrash("photo.jpg", false, "2026-10-30", {}) {
                    if (it.method == "MOVE") {
                        moves++
                        throw failure
                    }
                    WebDavTrashResponse(201)
                }
                fail("MOVE should have failed")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
            assertEquals(1, moves)
        }
    }
}
