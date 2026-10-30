package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferStreamTest {
    private fun metadata(size: Long = 12, mtime: Long = 10_000, directory: Boolean = false) = TransferFile(TransferLocation(ExplorerWindows.Kind.Smb, 7, "photo.jpg"), "photo.jpg", directory, size, mtime)

    @Test
    fun `skip off skips matching conflicts before choosing another name`() = runBlocking {
        for (preflightConflict in listOf(true, false)) {
            val attempts = mutableListOf<String>()
            var comparisons = 0
            val result = createTransferWithoutOverwrite(
                name = "photo.jpg",
                skipExisting = false,
                checkpoint = {},
                exists = { preflightConflict },
                matchesExisting = {
                    comparisons++
                    transferMetadataMatches(metadata(), metadata())
                },
                create = {
                    attempts += it
                    throw TransferNameConflict(it)
                },
            )
            assertEquals(null, result)
            assertEquals(1, comparisons)
            assertEquals(if (preflightConflict) emptyList<String>() else listOf("photo.jpg"), attempts)
        }
    }

    @Test
    fun `skip off still renames mismatches and skips an already uploaded numbered copy`() = runBlocking {
        val occupied = mapOf("photo.jpg" to metadata(size = 99), "photo (2).jpg" to metadata())
        var writes = 0
        val result = createTransferWithoutOverwrite(
            name = "photo.jpg",
            skipExisting = false,
            checkpoint = {},
            exists = { it in occupied },
            matchesExisting = { occupied[it]?.let { existing -> transferMetadataMatches(metadata(), existing) } == true },
            create = { writes++ },
        )
        assertEquals(null, result)
        assertEquals(0, writes)
        val renamed = createTransferWithoutOverwrite(
            name = "photo.jpg",
            skipExisting = false,
            checkpoint = {},
            exists = { it == "photo.jpg" },
            matchesExisting = { transferMetadataMatches(metadata(), metadata(mtime = 20_000)) },
            create = { it },
        )
        assertEquals("photo (2).jpg", renamed?.name)
    }

    @Test
    fun `matching requires known size and mtime and excludes directories`() {
        assertTrue(transferMetadataMatches(metadata(), metadata(mtime = 11_999)))
        for (other in listOf(metadata(size = 13), metadata(mtime = 12_000), metadata(size = -1), metadata(mtime = 0), metadata(directory = true))) {
            assertFalse(transferMetadataMatches(metadata(), other))
            assertFalse(transferMetadataMatches(other, metadata()))
        }
    }

    @Test
    fun `rename retries preflight conflicts and exclusive create races until a free name succeeds`() = runBlocking {
        val attempted = mutableListOf<String>()
        val occupied = mutableSetOf("photo.jpg", "photo (3).jpg")
        val result = createTransferWithoutOverwrite(
            name = "photo.jpg",
            skipExisting = false,
            checkpoint = {},
            exists = { it in occupied },
            create = {
                attempted += it
                if (it == "photo (2).jpg") {
                    occupied += it
                    throw TransferNameConflict(it)
                }
                occupied += it
                "new file"
            },
        )
        assertEquals(listOf("photo (2).jpg", "photo (4).jpg"), attempted)
        assertEquals("photo (4).jpg", result?.name)
        assertEquals("new file", result?.value)
        assertTrue("photo.jpg" in occupied)
        assertTrue("photo (3).jpg" in occupied)
    }

    @Test
    fun `skip handles both existing names and a concurrent create without touching the target`() = runBlocking {
        var creates = 0
        val existing = createTransferWithoutOverwrite("photo.jpg", true, {}, { true }) {
            creates++
        }
        assertEquals(null, existing)
        assertEquals(0, creates)
        val raced = createTransferWithoutOverwrite("photo.jpg", true, {}, { false }) {
            creates++
            throw TransferNameConflict(it)
        }
        assertEquals(null, raced)
        assertEquals(1, creates)
    }

    @Test
    fun `rename does not retry permission errors or other write failures`() = runBlocking {
        val failure = IOException("permission denied")
        var creates = 0
        try {
            createTransferWithoutOverwrite("photo.jpg", false, {}, { false }) {
                creates++
                throw failure
            }
            fail("Expected write error")
        } catch (error: IOException) {
            assertSame(failure, error)
            assertEquals(1, creates)
        }
    }

    @Test
    fun `stream keeps bytes intact using one bounded buffer and reports committed bytes`() = runBlocking {
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        var first: ByteArray? = null
        var written = 0L
        var reported = 0L
        copyTransferBlocks(ByteArrayInputStream(data), {}, {
            reported += it
            assertEquals(written, reported)
        }) { buffer, count ->
            assertTrue(buffer.size <= TRANSFER_BLOCK_BYTES)
            if (first == null) first = buffer else assertSame(first, buffer)
            output.write(buffer, 0, count)
            written += count
        }
        assertArrayEquals(data, output.toByteArray())
        assertEquals(data.size.toLong(), reported)
    }

    @Test
    fun `cancel stops before consuming more source bytes`() = runBlocking {
        val input = ByteArrayInputStream(ByteArray(4 * 1024 * 1024))
        var stop = false
        try {
            copyTransferBlocks(input, { if (stop) throw CancellationException() }, {}) { _, _ -> stop = true }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(3 * 1024 * 1024, input.available())
        }
    }

    @Test
    fun `sink failure is propagated without reporting uncommitted progress`() = runBlocking {
        val input = ByteArrayInputStream(ByteArray(4 * 1024 * 1024))
        var reported = 0L
        val failure = IOException("sink failed")
        try {
            copyTransferBlocks(input, {}, { reported += it }) { _, _ -> throw failure }
            fail("Expected sink failure")
        } catch (error: IOException) {
            assertSame(failure, error)
            assertEquals(0L, reported)
            assertEquals(3 * 1024 * 1024, input.available())
        }
    }

    @Test
    fun `server copy accepts different shares and source records but rejects other servers or credentials`() {
        val from = SmbSourceEntity(id = 1, displayName = "one", host = "server", share = "photos", username = "user", domain = "domain", addedAt = 0)
        val to = from.copy(id = 2, host = "SERVER", share = "backup", pathPrefix = "saved")
        assertTrue(canCopySmbOnServer(from, "password", to, "password"))
        assertFalse(canCopySmbOnServer(from, "password", to.copy(host = "other"), "password"))
        assertFalse(canCopySmbOnServer(from, "password", to.copy(port = 1445), "password"))
        assertFalse(canCopySmbOnServer(from, "password", to.copy(username = "other"), "password"))
        assertFalse(canCopySmbOnServer(from, "password", to.copy(domain = "other"), "password"))
        assertFalse(canCopySmbOnServer(from, "password", to, "other"))
    }
}
