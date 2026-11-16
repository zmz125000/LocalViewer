package com.hippo.ehviewer.webdav

import com.hippo.ehviewer.smb.SmbReadCancelledException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebDavArchiveCancellationTest {
    @Test
    fun directAndWrappedCancellationAbortTheObsoleteRead() {
        for (error in listOf(InterruptedException(), CancellationException(), SmbReadCancelledException())) {
            assertAborts(error)
            assertAborts(IOException("transport wrapper", IOException("nested", error)))
        }
    }

    @Test
    fun threadInterruptAbortsEvenWhenTransportOnlyReportsIoFailure() {
        try {
            Thread.currentThread().interrupt()
            assertAborts(IOException("transport stopped"))
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun ordinaryNetworkFailureRemainsAnOrdinaryFailure() {
        rethrowWebDavArchiveCancellation(IOException("connection reset"))
    }

    @Test
    fun interruptedRunBlockingWorkerCanRunTheNextRequest() {
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                try {
                    runBlocking {
                        started.countDown()
                        awaitCancellation()
                    }
                } catch (error: Throwable) {
                    assertAborts(error)
                }
                assertFalse(Thread.currentThread().isInterrupted)
                assertEquals(42, runBlocking { 42 })
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(5_000)
            assertFalse("Cancelled worker must stop waiting", worker.isAlive)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
    }

    private fun assertAborts(error: Throwable) {
        try {
            rethrowWebDavArchiveCancellation(error)
            fail("Expected the archive read cancellation signal")
        } catch (_: SmbReadCancelledException) {
            // The video window must stop retrying the obsolete offset.
        }
    }
}
