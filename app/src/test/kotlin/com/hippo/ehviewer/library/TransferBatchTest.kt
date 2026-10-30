package com.hippo.ehviewer.library

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferBatchTest {
    private fun file(kind: ExplorerWindows.Kind, size: Long) = TransferFile(TransferLocation(kind, 1, "photo.jpg"), "photo.jpg", false, size, 1234)

    @Test
    fun `only local files with missing metadata need preparation`() {
        val local = file(ExplorerWindows.Kind.Local, 0)
        assertFalse(local.needsLocalTransferMetadata())
        assertTrue(local.copy(size = -1).needsLocalTransferMetadata())
        assertTrue(local.copy(mtime = 0).needsLocalTransferMetadata())
        assertFalse(local.copy(directory = true, size = -1, mtime = 0).needsLocalTransferMetadata())
        for (kind in listOf(ExplorerWindows.Kind.Smb, ExplorerWindows.Kind.WebDav)) {
            assertFalse(file(kind, -1).needsLocalTransferMetadata())
        }
    }

    @Test
    fun `queued metadata overlaps the current transfer without waiting for the full list`() = runBlocking {
        withTimeout(5000) {
            val transferring = CompletableDeferred<Unit>()
            val nextPrepared = CompletableDeferred<Unit>()
            val transferred = mutableListOf<Int>()
            forEachPreparedTransfer(
                items = listOf(0, 1, 2),
                prepare = { item ->
                    if (item == 1) {
                        transferring.await()
                        nextPrepared.complete(Unit)
                    }
                    item * 10
                },
                transfer = { item, metadata ->
                    if (item == 0) {
                        transferring.complete(Unit)
                        nextPrepared.await()
                    }
                    assertEquals(item * 10, metadata)
                    transferred += item
                },
            )
            assertEquals(listOf(0, 1, 2), transferred)
        }
    }

    @Test
    fun `metadata lookahead stays bounded and stop cancels its producer`() = runBlocking {
        withTimeout(5000) {
            val prepared = AtomicInteger()
            val filled = CompletableDeferred<Unit>()
            val worker = launch {
                forEachPreparedTransfer(
                    items = (0..999).toList(),
                    lookAhead = 2,
                    prepare = {
                        if (prepared.incrementAndGet() == 4) filled.complete(Unit)
                        it
                    },
                    transfer = { _, _ -> awaitCancellation() },
                )
            }
            filled.await()
            assertEquals(4, prepared.get()) // Current file, two queued, one awaiting channel capacity.
            worker.cancelAndJoin()
            assertEquals(4, prepared.get())
            assertTrue(worker.isCancelled)
        }
    }

    @Test
    fun `one file metadata failure does not discard other prepared files`() = runBlocking {
        val transferred = mutableListOf<Int>()
        val failed = mutableListOf<Int>()
        forEachPreparedTransfer(
            items = listOf(0, 1, 2),
            prepare = { if (it == 1) Result.failure<Int>(IllegalStateException("Unreadable file")) else Result.success(it) },
            transfer = { item, metadata ->
                if (metadata.isSuccess) transferred += metadata.getOrThrow() else failed += item
            },
        )
        assertEquals(listOf(0, 2), transferred)
        assertEquals(listOf(1), failed)
    }

    @Test
    fun `small network batches overlap while large unknown and local transfers stay sequential`() {
        val local = file(ExplorerWindows.Kind.Local, 3 * 1024 * 1024)
        for (kind in listOf(ExplorerWindows.Kind.Smb, ExplorerWindows.Kind.WebDav)) {
            val remote = file(kind, local.size)
            assertEquals(4, transferBatchParallelism(listOf(local, local), kind))
            assertEquals(4, transferBatchParallelism(listOf(remote, remote), ExplorerWindows.Kind.Local))
            assertEquals(4, transferBatchParallelism(listOf(remote, remote), kind))
            assertEquals(1, transferBatchParallelism(listOf(local, local), kind, enabled = false))
            assertEquals(1, transferBatchParallelism(listOf(remote, remote), ExplorerWindows.Kind.Local, enabled = false))
            assertEquals(1, transferBatchParallelism(listOf(remote), ExplorerWindows.Kind.Local))
            assertEquals(1, transferBatchParallelism(listOf(remote, remote.copy(size = 100 * 1024 * 1024)), ExplorerWindows.Kind.Local))
            assertEquals(1, transferBatchParallelism(listOf(remote, remote.copy(size = -1)), ExplorerWindows.Kind.Local))
        }
        assertEquals(1, transferBatchParallelism(listOf(local, local), ExplorerWindows.Kind.Local))
    }

    @Test
    fun `a thousand files run exactly once in four sequential lanes`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val seen = ConcurrentHashMap.newKeySet<Int>()
        withTimeout(3000) {
            forEachTransferLane((0 until 1000).toList(), 4) { _, items ->
                if (entered.incrementAndGet() == 4) ready.complete(Unit)
                ready.await()
                for (item in items) assertTrue(seen.add(item))
            }
        }
        assertEquals(4, entered.get())
        assertEquals((0 until 1000).toSet(), seen)
    }

    @Test
    fun `stop cancels every lane and joins its cleanup`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val cleaned = AtomicInteger()
        val batch = launch(Dispatchers.Default) {
            forEachTransferLane((0 until 1000).toList(), 4) { _, _ ->
                try {
                    if (entered.incrementAndGet() == 4) ready.complete(Unit)
                    awaitCancellation()
                } finally {
                    cleaned.incrementAndGet()
                }
            }
        }
        withTimeout(3000) {
            ready.await()
            batch.cancelAndJoin()
        }
        assertEquals(4, cleaned.get())
    }
}
