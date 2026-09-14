package com.hippo.ehviewer.smb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbCancelCloseFileTest {
    @Test
    fun cancelClosesHandleBeforeBlockingWorkFinishes() = runBlocking {
        val closed = CountDownLatch(1)
        val file = AtomicReference<AutoCloseable?>(AutoCloseable { closed.countDown() })
        val job = launch(Dispatchers.IO) {
            val closer = coroutineContext.job.closeFileOnCancelling(file)
            try {
                Thread.sleep(10_000)
            } finally {
                closer.dispose()
            }
        }
        delay(50)
        job.cancel()
        assertTrue(
            "handle must close on cancelling, not after the 10s sleep",
            closed.await(1, TimeUnit.SECONDS),
        )
        job.join()
    }

    @Test
    fun disposeWithoutCancelDoesNotCloseHandle() = runBlocking {
        val closed = AtomicBoolean(false)
        val file = AtomicReference<AutoCloseable?>(AutoCloseable { closed.set(true) })
        val job = launch {
            val closer = coroutineContext.job.closeFileOnCancelling(file)
            closer.dispose()
        }
        job.join()
        delay(50)
        assertFalse(closed.get())
    }

    @Test
    fun armAfterCancelClosesHandle() {
        val closed = CountDownLatch(1)
        val file = AtomicReference<AutoCloseable?>(null)
        val job = Job().apply { cancel() }
        val closer = job.closeFileOnCancelling(file)
        armSmbFileForCancelClose(job, file, AutoCloseable { closed.countDown() })
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        closer.dispose()
    }
}
