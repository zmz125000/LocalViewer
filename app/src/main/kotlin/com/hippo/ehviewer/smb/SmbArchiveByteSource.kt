package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.hierynomus.smbj.share.File
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.openZipContainedFileSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 *   browse pool so external FUSE viewers keep working after LocalViewer backgrounds.
 * - [httpStickyPool] = true: same sticky TCP but limited by [SmbGateway] HTTP sticky pool
 *   (cap 4); used for loopback HTTP external video.
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
     * When true with [stickySession], use the capped HTTP sticky pool ([SmbGateway.withHttpStickyOpenFile]).
     */
    httpStickyPool: Boolean = false,
    /**
     * If [httpStickyPool]: wait for a free slot. Always true — one video, one lane.
     */
    httpStickyWait: Boolean = true,
    /**
     * When known (e.g. external PDF registration), skip a separate size open before
     * the first [readAt]. Must match the remote file.
     */
    knownSize: Long = -1L,
    /**
     * This handle belongs to a [SmbGateway.beginVideoPlay] generation. A newer play
     * force-closes the TCP so a stale HTTP GET cannot occupy the video NIO group.
     * PDF / non-video FUSE must leave this false.
     */
    videoPlay: Boolean = false,
    /**
     * Browse-pool cover / video-thumb I/O. Interactive reader or a new play can cancel
     * this borrow so it retries after they take the slot. Sticky sessions ignore this.
     */
    yieldable: Boolean = false,
    /**
     * Windowed readahead for sequential archive parsing. Off when a higher layer
     * (e.g. [com.hippo.ehviewer.library.BlockCacheArchiveByteSource]) owns caching.
     */
    readahead: Boolean = true,
) : ArchiveByteSource {
    private val raw: KeepOpenSmbFileSource?
    private val inner: ArchiveByteSource

    init {
        val zipMember = ZipAsDirListing.zipMemberPath(remoteRelativeFile)
        if (zipMember != null) {
            val (zipRel, memberRel) = zipMember
            raw = null
            inner = openZipContainedFileSource("smb:${source.id}:$zipRel", memberRel) {
                // Honor the outer flags. Video already windows in VideoDirectLink
                // (2 MiB × 28). Forcing readahead here slides a 16 MiB array on every
                // inflater/64 KiB ZIP read → LOS GC storm and 4K never starts.
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = zipRel,
                    pipeline = pipeline,
                    yieldable = yieldable,
                    stickySession = stickySession,
                    httpStickyPool = httpStickyPool,
                    httpStickyWait = httpStickyWait,
                    videoPlay = videoPlay,
                    readahead = readahead,
                )
            }
        } else {
            val smb = KeepOpenSmbFileSource(
                source,
                password,
                remoteRelativeFile,
                stickySession,
                httpStickyPool,
                httpStickyWait,
                knownSize,
                videoPlay,
                yieldable,
            )
            raw = smb
            inner = if (readahead) {
                ReadAheadArchiveByteSource(
                    inner = smb,
                    sequentialWindow = sequentialWindow,
                    preferSequential = preferSequential,
                    pipeline = pipeline,
                )
            } else {
                smb
            }
        }
    }

    override val size: Long get() = inner.size

    override val isRandomAccess: Boolean get() = inner.isRandomAccess

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = inner.readAt(offset, buf, off, len)

    override fun warm(offset: Long, length: Int) = inner.warm(offset, length)

    override fun dropQueuedReads() {
        raw?.dropQueuedReads()
    }

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
    private val httpStickyPool: Boolean = false,
    private val httpStickyWait: Boolean = true,
    knownSize: Long = -1L,
    private val videoPlay: Boolean = false,
    private val yieldable: Boolean = false,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val httpStickyLease = if (stickySession && httpStickyPool) {
        SmbGateway.newHttpStickyLease()
    } else {
        null
    }

    /** Active handle so close/deadline cancellation interrupts a blocking smbj read. */
    private val activeFile = AtomicReference<File?>(null)
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
                                activeFile.set(file)
                                opened = true
                                try {
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
                                                if (!isShareClosedError(e)) logcat("SmbArchive", e)
                                                op.result.completeExceptionally(e)
                                                if (isShareClosedError(e) || closed.get()) throw e
                                            }
                                        }
                                        suspend fun runBatch(first: Op) {
                                            val batch = ArrayList<Op>(READ_PIPELINE)
                                            batch.add(first)
                                            while (batch.size < READ_PIPELINE) {
                                                batch.add(ops.tryReceive().getOrNull() ?: break)
                                            }
                                            if (batch.size == 1) {
                                                handle(batch[0])
                                                return
                                            }
                                            // Concurrent File.read multiplexes on one smbj session
                                            // (message IDs / credits). readAsync is package-private.
                                            coroutineScope {
                                                for (op in batch) {
                                                    launch(Dispatchers.IO) { handle(op) }
                                                }
                                            }
                                        }
                                        if (stickySession) {
                                            while (isActive && !closed.get()) {
                                                val received = withTimeoutOrNull(STICKY_IDLE_PING_MS) {
                                                    ops.receiveCatching()
                                                }
                                                if (received == null) {
                                                    if (closed.get()) break
                                                    // Keepalive against NAS/NAT idle drop. Share may
                                                    // already be dead after dropStickySessions (screen
                                                    // off) — exit drain cleanly so the HTTP sticky
                                                    // permit is released; next demand reconnects.
                                                    try {
                                                        file.fileInformation.standardInformation.endOfFile
                                                    } catch (e: Throwable) {
                                                        if (closed.get() || !isActive) break
                                                        if (isShareClosedError(e)) {
                                                            logcat("SmbArchive") {
                                                                "sticky idle: transport gone, reconnect on demand"
                                                            }
                                                            // Queued reads must re-arm open (demand
                                                            // may already have been consumed).
                                                            if (!ops.isEmpty) demand.trySend(Unit)
                                                            break
                                                        }
                                                        logcat("SmbArchive", e)
                                                        throw e
                                                    }
                                                    continue
                                                }
                                                val op = received.getOrNull() ?: break
                                                runBatch(op)
                                            }
                                        } else {
                                            for (op in ops) {
                                                runBatch(op)
                                            }
                                        }
                                    }
                                } finally {
                                    activeFile.compareAndSet(file, null)
                                }
                            }
                            val playEpoch = if (videoPlay) SmbGateway.currentVideoPlayEpoch() else null
                            when {
                                stickySession && httpStickyPool -> {
                                    SmbGateway.withHttpStickyOpenFile(
                                        source,
                                        password,
                                        remote,
                                        waitForSlot = httpStickyWait,
                                        lease = checkNotNull(httpStickyLease),
                                        videoPlayEpoch = playEpoch,
                                        block = ::drain,
                                    )
                                }
                                stickySession -> {
                                    SmbGateway.withStickyOpenFile(
                                        source,
                                        password,
                                        remote,
                                        videoPlayEpoch = playEpoch,
                                        block = ::drain,
                                    )
                                }
                                else -> {
                                    SmbGateway.withOpenFile(
                                        source,
                                        password,
                                        remote,
                                        yieldable = yieldable && !stickySession,
                                        block = ::drain,
                                    )
                                }
                            }
                            break
                        } catch (e: Throwable) {
                            if (closed.get() || !isActive) throw e
                            // Share/transport death mid-session is expected after screen-off
                            // dropSticky / NAS idle; reconnect on next demand without Error spam.
                            if (isShareClosedError(e)) {
                                logcat("SmbArchive") {
                                    "sticky share closed (${if (opened) "reconnect on demand" else "retry open"}): ${e.message}"
                                }
                            } else {
                                logcat("SmbArchive", e)
                            }
                            if (opened) {
                                // The active request already received its failure. Do not spin
                                // reconnecting with no consumer; wait for fresh read demand.
                                // Re-arm if reads are already queued (demand may be empty).
                                if (!ops.isEmpty) demand.trySend(Unit)
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
                    // App ON_STOP / pool drop under an open drain — expected, not a fault.
                    if (isShareClosedError(e)) {
                        logcat("SmbArchive") { "share closed under drain: ${e.message}" }
                        continue
                    }
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
            // Prefetch cancel / proxy onRelease interrupt in-flight runBlocking — not a fault.
            if (closed.get() || e is InterruptedException || e.cause is InterruptedException) {
                Thread.interrupted() // clear flag so pooled workers stay usable
                return -1
            }
            // App background closes the browse pool under mid-read; caller soft-fails.
            if (isShareClosedError(e)) return -1
            logcat("SmbArchive", e)
            -1
        }
    }

    override fun dropQueuedReads() {
        if (closed.get()) return
        while (true) {
            val op = ops.tryReceive().getOrNull() ?: break
            op.result.complete(-1)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Pool ownership ends now; smbj/file teardown below is intentionally asynchronous.
        httpStickyLease?.let { SmbGateway.cancelHttpStickyLease(it) }
        ops.close()
        demand.close()
        failClosedSizeReady()
        activeFile.getAndSet(null)?.let { file ->
            // smbj close may itself touch a dead socket. Off-caller so Fuse timeout/onRelease
            // stays bounded. Shared pool — not Thread().start() per close.
            SmbAsyncClose.run { file.close() }
        }
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

        /** Outstanding SMB READs on one handle (credits / message IDs). */
        const val READ_PIPELINE = 4

        /**
         * Transient SMB READ blips (credit / stall) should not surface as Fuse EIO. Retry a
         * few times on the same handle before failing the op (share-closed still reconnects).
         */
        private suspend fun readFullyWithRetry(
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
                    delay(READ_RETRY_BACKOFF_MS * (attempt + 1L))
                }
            }
            throw last ?: IOException("SMB read failed")
        }

        private suspend fun readFully(
            file: File,
            fileOffset: Long,
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len <= READ_CHUNK) {
                return file.read(buf, fileOffset, off, len)
            }
            val starts = ArrayList<Int>()
            val sizes = ArrayList<Int>()
            var pos = 0
            while (pos < len) {
                val n = minOf(READ_CHUNK, len - pos)
                starts.add(pos)
                sizes.add(n)
                pos += n
            }
            val got = IntArray(sizes.size)
            coroutineScope {
                for (i in sizes.indices) {
                    launch(Dispatchers.IO) {
                        got[i] = file.read(
                            buf,
                            fileOffset + starts[i],
                            off + starts[i],
                            sizes[i],
                        )
                    }
                }
            }
            var total = 0
            for (i in sizes.indices) {
                val n = got[i]
                if (n <= 0) break
                total += n
                if (n < sizes[i]) break
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
