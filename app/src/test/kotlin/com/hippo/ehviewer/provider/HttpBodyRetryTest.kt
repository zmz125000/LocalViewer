package com.hippo.ehviewer.provider

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpBodyRetryTest {
    @Test
    fun midRangeFailureRetries() {
        assertTrue(httpBodyShouldRetryRead(-1, remaining = 1024L))
    }

    @Test
    fun playheadEofDoesNotReconnectSharedBody() {
        assertFalse(httpBodyShouldRetryRead(0, remaining = 1L))
        assertFalse(httpBodyShouldRetryRead(0, remaining = 1024L))
    }

    @Test
    fun finishedRangeDoesNotRetry() {
        assertFalse(httpBodyShouldRetryRead(0, remaining = 0L))
        assertFalse(httpBodyShouldRetryRead(-1, remaining = 0L))
        assertFalse(httpBodyShouldRetryRead(64, remaining = 1024L))
    }

    @Test
    fun headerProbeAndStartPlaybackAreNotSeeks() {
        val resumeAt = 50L * 1024 * 1024
        assertFalse(httpRangeIsPlaybackSeek(previousPlayhead = Long.MIN_VALUE, start = 0L))
        assertFalse(httpRangeIsPlaybackSeek(previousPlayhead = resumeAt, start = resumeAt))
    }

    @Test
    fun resumeAndPlayheadJumpAreSeeks() {
        val resumeAt = 50L * 1024 * 1024
        assertTrue(httpRangeIsPlaybackSeek(previousPlayhead = Long.MIN_VALUE, start = resumeAt))
        assertTrue(httpRangeIsPlaybackSeek(previousPlayhead = 0L, start = resumeAt))
    }

    @Test
    fun shortBodyIsNotACleanEnd() {
        assertTrue(httpIncompleteBodyAbortsClient(remaining = 1L))
        assertTrue(httpIncompleteBodyAbortsClient(remaining = 1024L))
        assertFalse(httpIncompleteBodyAbortsClient(remaining = 0L))
    }

    @Test
    fun smbRetryOutlastsThePauseWatchdog() {
        val pauseWatchdogMs = 15_000L
        assertTrue(HTTP_READ_RETRY_MS > pauseWatchdogMs)
        assertFalse(httpReadRetryGaveUp(pauseWatchdogMs, HTTP_READ_RETRY_MS))
        assertTrue(httpReadRetryGaveUp(HTTP_READ_RETRY_MS, HTTP_READ_RETRY_MS))
    }

    @Test
    fun incompleteBodyResetsInsteadOfCleanEof() {
        ServerSocket(0).use { server ->
            val accepted = CompletableFuture.supplyAsync { server.accept() }
            Socket("127.0.0.1", server.localPort).use { client ->
                client.soTimeout = 2_000
                val remote = accepted.get(2, TimeUnit.SECONDS)
                val out = remote.getOutputStream()
                val headers = "HTTP/1.1 206 Partial Content\r\nContent-Length: 100\r\n\r\n"
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(ByteArray(10))
                out.flush()
                abortHttpClient(remote)
                val input = client.getInputStream()
                val buf = ByteArray(200)
                var total = 0
                var reset = false
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                } catch (e: IOException) {
                    reset = e !is SocketTimeoutException
                }
                assertTrue("short 206 ended cleanly after $total bytes", reset)
                assertTrue(total < 100)
            }
        }
    }
}
