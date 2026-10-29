package com.hippo.ehviewer.smb

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Adapt the existing four-read SMB pipeline for upload consumers. One MiB of pipe
 * backpressure bounds read-ahead; the legacy reader's four MiB buffers remain unchanged.
 */
internal suspend fun <T> withSmbTransferInput(
    read: SmbRangeReader,
    size: Long,
    abortRead: () -> Unit,
    startOffset: Long = 0,
    consume: suspend (InputStream) -> T,
): T = supervisorScope {
    require(size >= 0 && startOffset >= 0)
    val pipe = SmbTransferPipe(SmbSequentialCopy.READ_CHUNK)
    val failure = AtomicReference<Throwable?>(null)
    val aborted = AtomicBoolean(false)
    val abortOnce = { if (aborted.compareAndSet(false, true)) abortRead() }
    val producer = launch(Dispatchers.IO) {
        try {
            val context = coroutineContext
            // A server may cap individual READ replies below 1 MiB. Complete each range
            // here so the unchanged legacy copier does not interpret that cap as EOF.
            val completeRead = SmbRangeReader { buffer, position, offset, length ->
                var filled = 0
                while (filled < length) {
                    context.ensureActive()
                    val count = try {
                        read.read(buffer, position + filled, offset + filled, length - filled)
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                        SmbAsyncClose.run { abortOnce() }
                        throw error
                    }
                    if (count <= 0) break
                    filled += count
                }
                if (filled == 0) -1 else filled
            }
            val copied = SmbSequentialCopy.copySuspending(
                read = completeRead,
                start = startOffset,
                maxBytes = size,
                isActive = { context.isActive },
            ) { buffer, offset, count ->
                context.ensureActive()
                pipe.write(buffer, offset, count)
            }
            context.ensureActive()
            if (copied != size) throw IOException("SMB source ended before the expected file size")
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        } finally {
            pipe.finish(failure.get())
        }
    }
    // A failed destination must also unblock outstanding source READs before join().
    val abort = AtomicReference<AutoCloseable?>(AutoCloseable { abortOnce() })
    val cancelClose = producer.closeFileOnCancelling(abort)
    try {
        consume(pipe).also { failure.get()?.let { error -> throw error } }
    } finally {
        // Wake blocked readers/writers before waiting for the producer.
        pipe.close()
        withContext(NonCancellable) { producer.cancelAndJoin() }
        cancelClose.dispose()
        abort.set(null)
    }
}
