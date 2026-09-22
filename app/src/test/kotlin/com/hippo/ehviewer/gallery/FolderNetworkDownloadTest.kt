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
        val libHdr = Semaphore(2)
        val prefetch = Semaphore(4)
    }

    private suspend fun Lanes.run(
        isSerial: Boolean,
        cacheOff: Boolean,
        libHdr: Boolean = false,
        block: suspend () -> Unit,
    ) = withFolderNetworkPermit(
        isSerial = { isSerial },
        cacheOff = cacheOff,
        libHdr = libHdr,
        serialSlots = serial,
        ramPrefetchSlots = ram,
        libHdrPrefetchSlots = this.libHdr,
        prefetchSlots = prefetch,
        block = block,
    )

    @Test
    fun `serial head takes the reserved slot`() = runBlocking {
        val lanes = Lanes()
        lanes.run(isSerial = true, cacheOff = true) {
            assertEquals(0, lanes.serial.availablePermits)
            assertEquals(RAM_PREFETCH_PERMITS, lanes.ram.availablePermits)
        }
        assertEquals(1, lanes.serial.availablePermits)
    }

    @Test
    fun `cache-off mate uses ram and leaves the reserved slot free`() = runBlocking {
        val lanes = Lanes()
        lanes.run(isSerial = false, cacheOff = true) {
            assertEquals(1, lanes.serial.availablePermits)
            assertEquals(RAM_PREFETCH_PERMITS - 1, lanes.ram.availablePermits)
        }
    }

    @Test
    fun `serial head waits on reserved slot instead of falling back`() = runBlocking {
        val lanes = Lanes()
        lanes.serial.acquire()
        val entered = AtomicInteger(0)
        val waiting = async {
            lanes.run(isSerial = true, cacheOff = true) {
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
    fun `cache-on decode-ahead uses prefetch not the reserved slot`() = runBlocking {
        val lanes = Lanes()
        lanes.run(isSerial = false, cacheOff = false) {
            assertEquals(1, lanes.serial.availablePermits)
            assertEquals(3, lanes.prefetch.availablePermits)
            assertEquals(RAM_PREFETCH_PERMITS, lanes.ram.availablePermits)
        }
    }

    @Test
    fun `cache-on lib-hdr decode-ahead uses the convert cap`() = runBlocking {
        val lanes = Lanes()
        lanes.run(isSerial = false, cacheOff = false, libHdr = true) {
            assertEquals(1, lanes.serial.availablePermits)
            assertEquals(1, lanes.libHdr.availablePermits)
            assertEquals(4, lanes.prefetch.availablePermits)
        }
    }

    @Test
    fun `cache-off third mate waits on ram not serial`() = runBlocking {
        val lanes = Lanes()
        val hold = CompletableDeferred<Unit>()
        val bothEntered = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        fun markEntered() {
            if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
        }
        val a = async {
            lanes.run(isSerial = false, cacheOff = true) {
                markEntered()
                hold.await()
            }
        }
        val b = async {
            lanes.run(isSerial = false, cacheOff = true) {
                markEntered()
                hold.await()
            }
        }
        bothEntered.await()
        assertEquals(0, lanes.ram.availablePermits)
        assertEquals(1, lanes.serial.availablePermits)
        val waiting = async {
            lanes.run(isSerial = false, cacheOff = true) {
                entered.incrementAndGet()
            }
        }
        yield()
        assertEquals(2, entered.get())
        assertTrue(waiting.isActive)
        assertEquals(1, lanes.serial.availablePermits)
        hold.complete(Unit)
        a.await()
        b.await()
        waiting.await()
        assertEquals(3, entered.get())
    }
}
