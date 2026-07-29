package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
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
 * Blocking random-access reads of one remote SMB archive for stream open.
 *
 * **Keeps a single SMB file handle open** for the whole reader session and serves
 * reads from a 2 MiB readahead window. The previous path opened/closed the remote
 * file on every libarchive chunk (~256 KiB) — each CREATE+CLOSE is a full RTT and
 * made CD scan + first-page extract extremely slow.
 */
class SmbArchiveByteSource(
    private val source: SmbSourceEntity,
    private val password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val sizeReady = CompletableDeferred<Long>()
    private val ops = Channel<Op>(capacity = 32)
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
                    // Readahead lives on the worker thread that owns [file].
                    var winStart = -1L
                    var winLen = 0
                    var win: ByteArray? = null
                    runBlocking {
                        for (op in ops) {
                            if (closed.get()) {
                                op.result.complete(-1)
                                continue
                            }
                            try {
                                val n = readWithWindow(
                                    file = file,
                                    fileSize = fileSize,
                                    offset = op.offset,
                                    buf = op.buf,
                                    off = op.off,
                                    len = op.len,
                                    winStart = { winStart },
                                    winLen = { winLen },
                                    win = { win },
                                    setWindow = { start, data, length ->
                                        winStart = start
                                        win = data
                                        winLen = length
                                    },
                                )
                                op.result.complete(n)
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
                // Drain pending ops so callers do not hang.
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
        val ok = ops.trySend(Op(offset, buf, off, toRead, result))
        if (ok.isFailure) {
            // Channel closed or full — fall back to blocking send.
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
        return try {
            runBlocking { result.await() }
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
        const val WINDOW = 2 * 1024 * 1024

        private fun readWithWindow(
            file: File,
            fileSize: Long,
            offset: Long,
            buf: ByteArray,
            off: Int,
            len: Int,
            winStart: () -> Long,
            winLen: () -> Int,
            win: () -> ByteArray?,
            setWindow: (start: Long, data: ByteArray?, length: Int) -> Unit,
        ): Int {
            val want = minOf(len.toLong(), fileSize - offset).toInt()
            if (want <= 0) return 0
            val w = win()
            val ws = winStart()
            val wl = winLen()
            if (w != null && offset >= ws && offset + want <= ws + wl) {
                System.arraycopy(w, (offset - ws).toInt(), buf, off, want)
                return want
            }
            val fetch = minOf(WINDOW.toLong(), fileSize - offset).toInt().coerceAtLeast(want)
            val fresh = ByteArray(fetch)
            val got = file.read(fresh, offset, 0, fetch)
            if (got <= 0) {
                setWindow(-1L, null, 0)
                return got
            }
            setWindow(offset, fresh, got)
            val n = minOf(want, got)
            System.arraycopy(fresh, 0, buf, off, n)
            return n
        }
    }
}
