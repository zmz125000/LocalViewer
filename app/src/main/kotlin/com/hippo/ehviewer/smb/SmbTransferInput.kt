package com.hippo.ehviewer.smb

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Adapt a rolling SMB read window for transfer consumers. One MiB of pipe
 * backpressure bounds read-ahead; gallery/reader buffering remains unchanged.
 */
internal suspend fun <T> withSmbTransferInput(
    read: SmbRangeReader,
    size: Long,
    abortRead: () -> Unit,
    startOffset: Long = 0,
    consume: suspend (InputStream) -> T,
): T = supervisorScope {
    require(size >= 0 && startOffset >= 0)
    val pipe = SmbTransferPipe(SmbTransferCopy.BLOCK_BYTES)
    val failure = AtomicReference<Throwable?>(null)
    val aborted = AtomicBoolean(false)
    val abortOnce = { if (aborted.compareAndSet(false, true)) abortRead() }
    val producer = launch(Dispatchers.IO) {
        try {
            val context = coroutineContext
            SmbTransferCopy.copy(
                read = read,
                start = startOffset,
                size = size,
                onFailure = { error ->
                    if (failure.compareAndSet(null, error)) SmbAsyncClose.run { abortOnce() }
                },
            ) { buffer, offset, count ->
                context.ensureActive()
                pipe.write(buffer, offset, count)
            }
            context.ensureActive()
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
