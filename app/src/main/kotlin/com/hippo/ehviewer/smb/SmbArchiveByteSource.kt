package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.hierynomus.smbj.share.File
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Random-access SMB archive source for stream open.
 *
 * Holds **one** open file for the reader session (via [SmbGateway.withOpenFile]) and
 * wraps it in [ReadAheadArchiveByteSource] for sequential/random windowing.
 * Dialects come from the shared gateway pool (SMB3 preferred when negotiated) —
 * smbj still uses SMB2-family message types for SMB 2.x/3.x.
 *
 * Reconnects when the host pool closes the DiskShare (e.g. app ON_STOP) so a later
 * resume does not fail with "DiskShare has already been closed".
 */
class SmbArchiveByteSource(
    source: SmbSourceEntity,
    password: String,
    remoteRelativeFile: String,
    /**
     * Solid / TAR chunk: always fixed sequential windows so traffic stays saturated
     * while decompress/parse runs.
     */
    preferSequential: Boolean = false,
    /** Pipeline next fixed window (reader solid/TAR). Off for cover thumbs. */
    pipeline: Boolean = true,
    /** Fixed window size (default 8 MiB). */
    sequentialWindow: Int = ReadAheadArchiveByteSource.SEQUENTIAL_WINDOW,
) : ArchiveByteSource {
    private val inner = ReadAheadArchiveByteSource(
        inner = KeepOpenSmbFileSource(source, password, remoteRelativeFile),
        sequentialWindow = sequentialWindow,
        preferSequential = preferSequential,
        pipeline = pipeline,
    )

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = inner.readAt(offset, buf, off, len)

    override fun warm(offset: Long, length: Int) = inner.warm(offset, length)

    override fun close() = inner.close()
}

/**
 * Single open handle + looped reads. No readahead (see [ReadAheadArchiveByteSource]).
 * All I/O is serialized on a worker that owns the pool borrow for the session.
 * Worker **reconnects** if the share/session dies under us.
 *
 * [close] completes [sizeReady] so cancellation during open cannot wait forever.
 */
private class KeepOpenSmbFileSource(
    private val source: SmbSourceEntity,
    private val password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val sizeReady = CompletableDeferred<Long>()
    private val ops = Channel<Op>(capacity = 64)
    private val worker: Job

    private data class Op(
        val offset: Long,
        val buf: ByteArray,
        val off: Int,
        val len: Int,
        val result: CompletableDeferred<Int>,
    )

    init {
        worker = scope.launch {
            var backoffMs = 50L
            while (isActive && !closed.get()) {
                try {
                    SmbGateway.withOpenFile(source, password, remote) { file, fileSize ->
                        if (closed.get()) {
                            runCatching { file.close() }
                            return@withOpenFile
                        }
                        if (!sizeReady.isCompleted) sizeReady.complete(fileSize)
                        backoffMs = 50L
                        // Blocking drain: withOpenFile callback is not a suspend lambda.
                        runBlocking {
                            for (op in ops) {
                                if (closed.get()) {
                                    op.result.complete(-1)
                                    continue
                                }
                                try {
                                    op.result.complete(
                                        readFully(file, op.offset, op.buf, op.off, op.len),
                                    )
                                } catch (e: Throwable) {
                                    logcat("SmbArchive", e)
                                    if (isShareClosedError(e) || closed.get()) {
                                        op.result.completeExceptionally(e)
                                        // Exit withOpenFile so outer loop reopens the share.
                                        throw e
                                    }
                                    op.result.completeExceptionally(e)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (closed.get() || !isActive) break
                    logcat("SmbArchive", e)
                    if (!sizeReady.isCompleted && !isShareClosedError(e)) {
                        sizeReady.completeExceptionally(e)
                        // Fail pending ops and stop — non-recoverable open error.
                        for (op in ops) {
                            op.result.completeExceptionally(e)
                        }
                        break
                    }
                    // Share/session gone (pool ON_STOP, idle kill): wait and reconnect.
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(2_000L)
                }
            }
            failClosedSizeReady()
            // Source closed or worker ending: fail anything still waiting.
            for (op in ops) {
                op.result.complete(-1)
            }
        }
    }

    override val size: Long
        get() {
            if (closed.get() && !sizeReady.isCompleted) {
                throw IOException("SMB archive source closed")
            }
            return runBlocking { sizeReady.await() }
        }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (closed.get()) return -1
        val fileSize = try {
            size
        } catch (_: Throwable) {
            return -1
        }
        if (offset >= fileSize) return 0
        val toRead = minOf(len.toLong(), fileSize - offset).toInt()
        val result = CompletableDeferred<Int>()
        return try {
            runBlocking {
                if (closed.get()) return@runBlocking -1
                ops.send(Op(offset, buf, off, toRead, result))
                result.await()
            }
        } catch (e: Throwable) {
            if (closed.get()) return -1
            logcat("SmbArchive", e)
            -1
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ops.close()
        failClosedSizeReady()
        worker.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun failClosedSizeReady() {
        if (!sizeReady.isCompleted) {
            sizeReady.completeExceptionally(IOException("SMB archive source closed"))
        }
    }

    private companion object {
        /**
         * Per-op size for smbj. Use a large chunk so an 8 MiB readahead window is only a
         * few READ requests (keeps multi-credit SMB3 busy instead of 64 KiB chatter).
         */
        const val READ_CHUNK = 2 * 1024 * 1024

        private fun readFully(
            file: File,
            fileOffset: Long,
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            var total = 0
            while (total < len) {
                val chunk = minOf(READ_CHUNK, len - total)
                val n = file.read(buf, fileOffset + total, off + total, chunk)
                if (n <= 0) break
                total += n
            }
            return total
        }

        private fun isShareClosedError(e: Throwable): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                val msg = cur.message.orEmpty()
                if (msg.contains("DiskShare has already been closed", ignoreCase = true) ||
                    msg.contains("Share has already been closed", ignoreCase = true) ||
                    msg.contains("Connection closed", ignoreCase = true) ||
                    msg.contains("Transport is closed", ignoreCase = true) ||
                    msg.contains("Socket closed", ignoreCase = true)
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }
    }
}
