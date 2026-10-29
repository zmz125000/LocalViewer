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
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferBatchTest {
    private fun file(kind: ExplorerWindows.Kind, size: Long) = TransferFile(TransferLocation(kind, 1, "photo.jpg"), "photo.jpg", false, size, 1234)

    @Test
    fun `small SMB batches overlap while large unknown and unrelated transfers stay sequential`() {
        val local = file(ExplorerWindows.Kind.Local, 3 * 1024 * 1024)
        val smb = file(ExplorerWindows.Kind.Smb, local.size)
        assertEquals(4, transferBatchParallelism(listOf(local, local), ExplorerWindows.Kind.Smb))
        assertEquals(1, transferBatchParallelism(listOf(local, local), ExplorerWindows.Kind.Smb, enabled = false))
        assertEquals(1, transferBatchParallelism(listOf(smb, smb), ExplorerWindows.Kind.Local, enabled = false))
        assertEquals(4, transferBatchParallelism(listOf(smb, smb), ExplorerWindows.Kind.Local))
        assertEquals(1, transferBatchParallelism(listOf(smb), ExplorerWindows.Kind.Local))
        assertEquals(1, transferBatchParallelism(listOf(smb, smb.copy(size = 100 * 1024 * 1024)), ExplorerWindows.Kind.Local))
        assertEquals(1, transferBatchParallelism(listOf(smb, smb.copy(size = -1)), ExplorerWindows.Kind.Local))
        assertEquals(1, transferBatchParallelism(listOf(local, local), ExplorerWindows.Kind.WebDav))
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
