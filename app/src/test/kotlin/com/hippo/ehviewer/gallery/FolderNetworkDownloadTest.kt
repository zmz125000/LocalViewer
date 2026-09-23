package com.hippo.ehviewer.gallery

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNetworkDownloadTest {
    private class Lanes {
        val serial = Semaphore(1)
        val ram = Semaphore(RAM_PREFETCH_PERMITS)
        val libHdr = Semaphore(LIB_HDR_PREFETCH_PERMITS)
        val prefetch = Semaphore(4)
    }

    private suspend fun Lanes.run(
        rank: Int,
        cacheOff: Boolean,
        libHdr: Boolean = false,
        block: suspend () -> Unit,
    ) = withFolderNetworkPermit(
        rank = { rank },
        cacheOff = cacheOff,
        libHdr = libHdr,
        serialSlots = serial,
        ramPrefetchSlots = ram,
        libHdrPrefetchSlots = this.libHdr,
        prefetchSlots = prefetch,
        prefetchPermitCount = 4,
        block = block,
    )

    @Test
    fun `rank 0 takes the reserved slot`() = runBlocking {
        val lanes = Lanes()
        lanes.run(rank = 0, cacheOff = true) {
            assertEquals(0, lanes.serial.availablePermits)
            assertEquals(RAM_PREFETCH_PERMITS, lanes.ram.availablePermits)
        }
        assertEquals(1, lanes.serial.availablePermits)
    }

    @Test
    fun `cache-off next page uses ram and leaves the reserved slot free`() = runBlocking {
        val lanes = Lanes()
        lanes.run(rank = 1, cacheOff = true) {
            assertEquals(1, lanes.serial.availablePermits)
            assertEquals(RAM_PREFETCH_PERMITS - 1, lanes.ram.availablePermits)
        }
    }

    @Test
    fun `rank 0 waits on the reserved slot instead of falling back`() = runBlocking {
        val lanes = Lanes()
        lanes.serial.acquire()
        val entered = AtomicInteger(0)
        val waiting = async {
            lanes.run(rank = 0, cacheOff = true) {
                entered.incrementAndGet()
            }
        }
        yield()
        yield()
        assertEquals(0, entered.get())
        assertTrue(waiting.isActive)
        assertEquals(RAM_PREFETCH_PERMITS, lanes.ram.availablePermits)
        lanes.serial.release()
        waiting.await()
        assertEquals(1, entered.get())
    }

    @Test
    fun `cache-on next page uses prefetch not the reserved slot`() = runBlocking {
        val lanes = Lanes()
        lanes.run(rank = 1, cacheOff = false) {
            assertEquals(1, lanes.serial.availablePermits)
            assertEquals(3, lanes.prefetch.availablePermits)
            assertEquals(RAM_PREFETCH_PERMITS, lanes.ram.availablePermits)
        }
    }

    @Test
    fun `cache-on lib-hdr next page uses the convert cap`() = runBlocking {
        val lanes = Lanes()
        lanes.run(rank = 1, cacheOff = false, libHdr = true) {
            assertEquals(1, lanes.serial.availablePermits)
            assertEquals(LIB_HDR_PREFETCH_PERMITS - 1, lanes.libHdr.availablePermits)
            assertEquals(4, lanes.prefetch.availablePermits)
        }
    }

    @Test
    fun `cache-off window fills in order and the next page waits`() = runBlocking {
        val lanes = Lanes()
        val hold = CompletableDeferred<Unit>()
        val allEntered = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        val window = 1 + RAM_PREFETCH_PERMITS
        fun markEntered() {
            if (entered.incrementAndGet() == window) allEntered.complete(Unit)
        }
        val running = (0 until window).map { rank ->
            async {
                lanes.run(rank = rank, cacheOff = true) {
                    markEntered()
                    hold.await()
                }
            }
        }
        allEntered.await()
        assertEquals(0, lanes.serial.availablePermits)
        assertEquals(0, lanes.ram.availablePermits)
        val waiting = async {
            lanes.run(rank = window, cacheOff = true) {
                entered.incrementAndGet()
            }
        }
        yield()
        yield()
        assertEquals(window, entered.get())
        assertTrue(waiting.isActive)
        waiting.cancel()
        hold.complete(Unit)
        running.forEach { it.await() }
    }
}
