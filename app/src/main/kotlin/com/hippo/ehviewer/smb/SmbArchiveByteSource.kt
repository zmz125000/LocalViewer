package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hierynomus.smbj.share.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Random-access SMB archive source for stream open.
 *
 * Holds **one** open file for the reader session (via [SmbGateway.withOpenFile]) and
 * wraps it in [ReadAheadArchiveByteSource] for sequential/random windowing.
 * Dialects come from the shared gateway pool (SMB3 preferred when negotiated) —
 * smbj still uses SMB2-family message types for SMB 2.x/3.x.
 */
class SmbArchiveByteSource(
    source: SmbSourceEntity,
    password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val inner = ReadAheadArchiveByteSource(
        KeepOpenSmbFileSource(source, password, remoteRelativeFile),
    )

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
        inner.readAt(offset, buf, off, len)

    override fun warm(offset: Long, length: Int) = inner.warm(offset, length)

    override fun close() = inner.close()
}

/**
 * Single open handle + looped reads. No readahead (see [ReadAheadArchiveByteSource]).
 * All I/O is serialized on a worker that owns the pool borrow for the session.
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
            try {
                SmbGateway.withOpenFile(source, password, remote) { file, fileSize ->
                    sizeReady.complete(fileSize)
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
                                logcat(e)
                                op.result.completeExceptionally(e)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                logcat(e)
                if (!sizeReady.isCompleted) sizeReady.completeExceptionally(e)
                for (op in ops) {
                    op.result.completeExceptionally(e)
                }
            }
        }
    }

    override val size: Long
        get() = runBlocking { sizeReady.await() }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (closed.get()) return -1
        val fileSize = size
        if (offset >= fileSize) return 0
        val toRead = minOf(len.toLong(), fileSize - offset).toInt()
        val result = CompletableDeferred<Int>()
        return try {
            runBlocking {
                ops.send(Op(offset, buf, off, toRead, result))
                result.await()
            }
        } catch (e: Throwable) {
            logcat(e)
            -1
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ops.close()
        worker.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        /** Per-op size; negotiated buffer is often ≤1 MiB. Loop for larger windows. */
        const val READ_CHUNK = 1024 * 1024

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
    }
}
