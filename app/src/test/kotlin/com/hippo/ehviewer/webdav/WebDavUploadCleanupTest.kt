package com.hippo.ehviewer.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebDavUploadCleanupTest {
    @Test
    fun interruptedPutThatCommitsAfterAnEarly404IsStillDeleted() = runBlocking {
        var now = 0L
        var serverCommitted = false
        var fileExists = false
        var deleted = 0
        var attempts = 0
        val cleaned = cleanupWebDavUpload(
            putCompleted = false,
            pause = { elapsed ->
                now += elapsed
                if (now >= 1500 && !serverCommitted) {
                    serverCommitted = true
                    fileExists = true
                }
            },
            delete = {
                attempts++
                if (fileExists) {
                    fileExists = false
                    deleted++
                    204
                } else {
                    404
                }
            },
        )
        assertTrue(cleaned)
        assertTrue(serverCommitted)
        assertFalse(fileExists)
        assertEquals(1, deleted)
        assertEquals(7, attempts)
    }

    @Test
    fun serverRecreatingAFileAfterSuccessfulDeleteIsHandled() = runBlocking {
        var fileExists = true
        var attempts = 0
        var deleted = 0
        assertTrue(
            cleanupWebDavUpload(
                putCompleted = false,
                pause = { if (attempts == 1) fileExists = true },
                delete = {
                    attempts++
                    if (fileExists) {
                        fileExists = false
                        deleted++
                        204
                    } else {
                        404
                    }
                },
            ),
        )
        assertEquals(2, deleted)
        assertFalse(fileExists)
    }

    @Test
    fun locksAndTransientNetworkErrorsAreRetried() = runBlocking {
        var attempts = 0
        val failures = mutableListOf<Throwable>()
        assertTrue(
            cleanupWebDavUpload(
                putCompleted = true,
                pause = {},
                delete = {
                    when (++attempts) {
                        1 -> 423
                        2 -> throw IOException("connection reset")
                        else -> 204
                    }
                },
                onFailure = { failures += it },
            ),
        )
        assertEquals(3, attempts)
        assertEquals(2, failures.size)
    }

    @Test
    fun completedPutNeedsOnlyOneSuccessfulDelete() = runBlocking {
        for (status in listOf(204, 404)) {
            var attempts = 0
            assertTrue(
                cleanupWebDavUpload(true, {
                    attempts++
                    status
                }, pause = { fail("No retry needed") }),
            )
            assertEquals(1, attempts)
        }
    }

    @Test
    fun persistentFailuresAreBoundedAndReported() = runBlocking {
        var attempts = 0
        assertFalse(
            cleanupWebDavUpload(false, {
                attempts++
                403
            }, pause = {}),
        )
        assertEquals(7, attempts)
    }

    @Test
    fun cleanupTimeoutCancellationIsNotSwallowed() = runBlocking {
        val cancellation = CancellationException("cleanup deadline")
        try {
            cleanupWebDavUpload(false, { throw cancellation }, pause = {})
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }
}
