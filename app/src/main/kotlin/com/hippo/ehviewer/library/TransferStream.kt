package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal const val TRANSFER_BLOCK_BYTES = 1024 * 1024
internal const val TRANSFER_WRITE_WINDOW = 16

/** WebDAV body channels already provide backpressure; use large blocks for their upload stream. */
internal suspend fun copyTransferBlocks(
    input: InputStream,
    checkpoint: suspend () -> Unit,
    progress: (Long) -> Unit,
    write: suspend (ByteArray, Int) -> Unit,
) {
    val buffer = ByteArray(TRANSFER_BLOCK_BYTES)
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        if (count < 0) return
        if (count == 0) continue
        checkpoint()
        write(buffer, count)
        progress(count.toLong())
    }
}

/**
 * A small sliding window on one SMB handle, not a connection pool. Pin each buffer until
 * acknowledged, then reuse it. Source reads overlap pending writes; RAM is bounded at 16 MiB.
 */
internal suspend fun copyTransferBlocksPipelined(
    input: InputStream,
    checkpoint: suspend () -> Unit,
    progress: (Long) -> Unit,
    startOffset: Long = 0,
    maxBytes: Long = Long.MAX_VALUE,
    timeoutMillis: Long = 120_000,
    submit: (ByteArray, Long, Int) -> Future<Long>,
) {
    require(startOffset >= 0 && maxBytes >= 0 && timeoutMillis > 0)
    class Pending(val buffer: ByteArray, val count: Int, val future: Future<Long>)
    val free = ArrayDeque<ByteArray>()
    val pending = ArrayDeque<Pending>()
    var allocated = 0
    var offset = startOffset
    var remaining = maxBytes

    suspend fun acknowledge() {
        checkpoint()
        val head = pending.first()
        val written = try {
            runInterruptible(Dispatchers.IO) { head.future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        } catch (error: ExecutionException) {
            // Coroutine stack recovery can add another ExecutionException wrapper.
            var cause: Throwable = error
            while (cause is ExecutionException && cause.cause != null) cause = cause.cause!!
            throw cause
        } catch (error: TimeoutException) {
            throw java.io.IOException("SMB upload acknowledgement timed out", error)
        }
        check(written == head.count.toLong()) { "SMB write acknowledged $written of ${head.count} bytes" }
        pending.removeFirst()
        progress(written)
        free.addLast(head.buffer)
    }

    try {
        while (remaining > 0) {
            checkpoint()
            if (pending.size == TRANSFER_WRITE_WINDOW) acknowledge()
            val buffer = if (free.isNotEmpty()) {
                free.removeFirst()
            } else {
                check(allocated < TRANSFER_WRITE_WINDOW)
                allocated++
                ByteArray(TRANSFER_BLOCK_BYTES)
            }
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            var count = 0
            while (count < requested) {
                checkpoint()
                val read = runInterruptible(Dispatchers.IO) { input.read(buffer, count, requested - count) }
                if (read < 0) break
                if (read > 0) count += read
            }
            if (count == 0) break
            checkpoint()
            pending.addLast(Pending(buffer, count, submit(buffer, offset, count)))
            offset += count
            remaining -= count
            if (count < requested) break
        }
        if (maxBytes != Long.MAX_VALUE && remaining > 0) throw java.io.IOException("SMB source ended before the expected file size")
        while (pending.isNotEmpty()) acknowledge()
    } finally {
        // Never replay consumed input or leave unobserved writes after failure/stop.
        pending.forEach { runCatching { it.future.cancel(true) } }
    }
}

internal fun canCopySmbOnServer(from: SmbSourceEntity, fromPassword: String, to: SmbSourceEntity, toPassword: String): Boolean = from.host.trim().equals(to.host.trim(), ignoreCase = true) && from.port == to.port &&
    from.username == to.username && from.domain == to.domain && fromPassword == toPassword

/** The destination rejected an exclusive create; callers may skip or choose another name. */
class TransferNameConflict(name: String) : java.io.IOException("Destination already exists: $name")

internal data class TransferCreation<T>(val name: String, val value: T)

/** Both preflight collisions and exclusive-create races use the same skip/rename policy. */
internal suspend fun <T> createTransferWithoutOverwrite(
    name: String,
    skipExisting: Boolean,
    checkpoint: suspend () -> Unit,
    exists: suspend (String) -> Boolean,
    create: suspend (String) -> T,
): TransferCreation<T>? {
    var candidate = name
    var number = 1L
    while (true) {
        checkpoint()
        if (!exists(candidate)) {
            try {
                return TransferCreation(candidate, create(candidate))
            } catch (_: TransferNameConflict) {
                // Someone created the target between exists() and create().
            }
        }
        if (skipExisting) return null
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        candidate = "${name.take(dot)} (${++number})${name.drop(dot)}"
    }
}
