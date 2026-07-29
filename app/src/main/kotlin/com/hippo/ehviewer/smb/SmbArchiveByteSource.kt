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
 * Keeps a single SMB file handle open. Sequential runs use a 2 MiB window filled
 * with looped SMB2 READs (same idea as folder download streaming). Random seeks
 * (EOCD / local headers) use a small window so listing does not re-download the zip.
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
        /** Match folder-gallery sequential throughput (large SMB2 READs). */
        const val SEQUENTIAL_WINDOW = 2 * 1024 * 1024

        /** EOCD / ZIP local headers only. */
        const val RANDOM_WINDOW = 64 * 1024

        /** Cap per SMB2 READ (negotiated buffer is often ≤1 MiB). */
        const val SMB_READ_CHUNK = 1024 * 1024

        /** Fill [buf] with looped [File.read] — one call may return less than requested. */
        private fun readFully(
            file: File,
            fileOffset: Long,
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            var total = 0
            while (total < len) {
                val chunk = minOf(SMB_READ_CHUNK, len - total)
                val n = file.read(buf, fileOffset + total, off + total, chunk)
                if (n <= 0) break
                total += n
            }
            return total
        }

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
            val sequential = w != null && offset == ws + wl
            val window = if (sequential) SEQUENTIAL_WINDOW else RANDOM_WINDOW
            val fetch = minOf(window.toLong(), fileSize - offset).toInt().coerceAtLeast(want)
            val fresh = ByteArray(fetch)
            val got = readFully(file, offset, fresh, 0, fetch)
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
