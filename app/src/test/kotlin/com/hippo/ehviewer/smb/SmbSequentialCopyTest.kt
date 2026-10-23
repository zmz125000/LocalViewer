package com.hippo.ehviewer.smb

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SmbSequentialCopyTest {
    @Test
    fun stopsWhenInactive() = runBlocking {
        val calls = AtomicInteger(0)
        val reader = SmbRangeReader { buf, _, off, len ->
            calls.incrementAndGet()
            buf.fill(1, off, off + len)
            len
        }
        var batches = 0
        val copied = SmbSequentialCopy.copySuspending(
            read = reader,
            start = 0L,
            maxBytes = 8L * 1024 * 1024,
            isActive = { batches < 1 },
        ) { _, _, _ -> batches++ }
        assertEquals(SmbSequentialCopy.READ_CHUNK.toLong() * SmbSequentialCopy.READ_PIPELINE, copied)
        assertEquals(SmbSequentialCopy.READ_PIPELINE, calls.get())
    }
}
