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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Random-access SMB archive source for stream open.
 *
 * Holds **one** open file for the reader session and wraps it in
 * [ReadAheadArchiveByteSource] for sequential/random windowing.
 *
 * - Default: [SmbGateway.withOpenFile] on the **shared host pool** (browse/reader).
 *   Pool is dropped on app [Lifecycle.Event.ON_STOP].
 * - [stickySession] = true: [SmbGateway.withStickyOpenFile] — dedicated TCP outside the
 *   pool so external FUSE PDF viewers keep working after LocalViewer backgrounds.
 *
 * Reconnects when the share dies under us so a later resume does not stick on
 * "DiskShare has already been closed".
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
    /**
     * Dedicated session outside the shared pool (survives app background).
     * Use for [com.hippo.ehviewer.provider.StreamDocumentProvider] / external apps.
     */
    stickySession: Boolean = false,
    /**
     * When known (e.g. external PDF registration), skip a separate size open before
     * the first [readAt]. Must match the remote file.
     */
    knownSize: Long = -1L,
    /**
     * Windowed readahead for sequential archive parsing. Off when a higher layer
     * (e.g. [com.hippo.ehviewer.library.BlockCacheArchiveByteSource]) owns caching.
     */
    readahead: Boolean = true,
) : ArchiveByteSource {
    private val raw = KeepOpenSmbFileSource(
        source,
        password,
        remoteRelativeFile,
        stickySession,
        knownSize,
    )
    private val inner: ArchiveByteSource = if (readahead) {
        ReadAheadArchiveByteSource(
            inner = raw,
            sequentialWindow = sequentialWindow,
            preferSequential = preferSequential,
            pipeline = pipeline,
        )
    } else {
        raw
    }

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
    private val stickySession: Boolean = false,
    knownSize: Long = -1L,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val sizeReady = CompletableDeferred<Long>().also { deferred ->
        if (knownSize > 0L) deferred.complete(knownSize)
    }
    private val ops = Channel<Op>(capacity = 64)

    /** Opens/reopens the remote handle only when size/read demand exists. */
    private val demand = Channel<Unit>(capacity = Channel.CONFLATED)
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
            while (isActive && !closed.get()) {
                if (demand.receiveCatching().getOrNull() == null) break
                var openAttempts = 0
                try {
                    while (isActive && !closed.get()) {
                        var opened = false
                        try {
                            // Sticky: dedicated TCP for FUSE/external viewers (not ON_STOP pool).
                            // Default: shared host pool for in-app reader/cover.
                            fun drain(file: com.hierynomus.smbj.share.File, fileSize: Long) {
                                opened = true
                                if (closed.get()) {
                                    runCatching { file.close() }
                                    return
                                }
                                if (!sizeReady.isCompleted) sizeReady.complete(fileSize)
                                // Blocking drain: open-file callback is not a suspend lambda.
                                // Sticky sessions idle-ping so NAS/NAT idle timeouts do not kill the
                                // handle during player buffer periods (external video can pause I/O
                                // for minutes while still holding the Fuse FD).
                                runBlocking {
                                    suspend fun handle(op: Op) {
                                        demand.tryReceive()
                                        if (closed.get()) {
                                            op.result.complete(-1)
                                            return
                                        }
                                        try {
                                            op.result.complete(
                                                readFullyWithRetry(file, op.offset, op.buf, op.off, op.len),
                                            )
                                        } catch (e: Throwable) {
                                            logcat("SmbArchive", e)
                                            if (isShareClosedError(e) || closed.get()) {
                                                op.result.completeExceptionally(e)
                                                throw e
                                            }
                                            op.result.completeExceptionally(e)
                                        }
                                    }
                                    if (stickySession) {
                                        while (isActive && !closed.get()) {
                                            val op = withTimeoutOrNull(STICKY_IDLE_PING_MS) {
                                                ops.receiveCatching().getOrNull()
                                            }
                                            if (op == null) {
                                                if (ops.isClosedForReceive || closed.get()) break
                                                try {
                                                    file.fileInformation.standardInformation.endOfFile
                                                } catch (e: Throwable) {
                                                    logcat("SmbArchive", e)
                                                    throw e
                                                }
                                                continue
                                            }
                                            handle(op)
                                        }
                                    } else {
                                        for (op in ops) {
                                            handle(op)
                                        }
                                    }
                                }
                            }
                            if (stickySession) {
                                SmbGateway.withStickyOpenFile(source, password, remote, ::drain)
                            } else {
                                SmbGateway.withOpenFile(source, password, remote, ::drain)
                            }
                            break
                        } catch (e: Throwable) {
                            if (closed.get() || !isActive) throw e
                            logcat("SmbArchive", e)
                            if (opened) {
                                // The active request already received its failure. Do not spin
                                // reconnecting with no consumer; wait for fresh read demand.
                                break
                            }
                            openAttempts++
                            if (!isShareClosedError(e) || openAttempts >= MAX_OPEN_ATTEMPTS) {
                                if (!sizeReady.isCompleted) sizeReady.completeExceptionally(e)
                                while (true) {
                                    val op = ops.tryReceive().getOrNull() ?: break
                                    op.result.completeExceptionally(e)
                                }
                                ops.close(e)
                                demand.close(e)
                                return@launch
                            }
                            delay(OPEN_RETRY_BACKOFF_MS * openAttempts)
                        }
                    }
                } catch (e: Throwable) {
                    if (closed.get() || !isActive) break
                    logcat("SmbArchive", e)
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
            if (!sizeReady.isCompleted) demand.trySend(Unit)
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
                demand.trySend(Unit)
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
        demand.close()
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
        const val MAX_OPEN_ATTEMPTS = 2
        const val OPEN_RETRY_BACKOFF_MS = 100L
        const val READ_ATTEMPTS = 3
        const val READ_RETRY_BACKOFF_MS = 30L
        /** Sticky external stream: ping before typical NAS idle drop (~2–5 min). */
        const val STICKY_IDLE_PING_MS = 45_000L

        /**
         * Per-op size for smbj. Use a large chunk so an 8 MiB readahead window is only a
         * few READ requests (keeps multi-credit SMB3 busy instead of 64 KiB chatter).
         */
        const val READ_CHUNK = 2 * 1024 * 1024

        /**
         * Transient SMB READ blips (credit / stall) should not surface as Fuse EIO. Retry a
         * few times on the same handle before failing the op (share-closed still reconnects).
         */
        private fun readFullyWithRetry(
            file: File,
            fileOffset: Long,
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            var last: Throwable? = null
            for (attempt in 0 until READ_ATTEMPTS) {
                try {
                    val n = readFully(file, fileOffset, buf, off, len)
                    // n==0 on a positive request is rare; treat as retryable empty.
                    if (n > 0 || len == 0) return n
                    last = IOException("SMB read returned 0 at offset=$fileOffset len=$len")
                } catch (e: Throwable) {
                    if (isShareClosedError(e)) throw e
                    last = e
                }
                if (attempt < READ_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(READ_RETRY_BACKOFF_MS * (attempt + 1L))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            throw last ?: IOException("SMB read failed")
        }

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
