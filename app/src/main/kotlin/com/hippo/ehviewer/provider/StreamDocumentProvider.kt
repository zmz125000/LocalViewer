package com.hippo.ehviewer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BlockCacheArchiveByteSource
import com.hippo.ehviewer.library.VideoDirectLinkByteSource
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Exposes a document as a grantable `content://` URI for external viewers.
 *
 * Local/SAF documents return their real seekable descriptor. Network documents use
 * [StorageManager.openProxyFileDescriptor] (AppFuse / MiX-style direct link).
 *
 * - **Video:** [VideoDirectLinkByteSource] — sliding window + one-lane forward prefetch.
 * - **PDF / other:** [BlockCacheArchiveByteSource] — sparse LRU for random probes.
 */
class StreamDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val token = tokenOf(uri) ?: return null
        return StreamDocumentRegistry.get(token)?.mimeType
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val token = tokenOf(uri) ?: return null
        val entry = StreamDocumentRegistry.get(token) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val values = arrayOfNulls<Any>(columns.size)
        for (i in columns.indices) {
            values[i] = when (columns[i]) {
                OpenableColumns.DISPLAY_NAME -> entry.displayName
                OpenableColumns.SIZE -> if (entry.sizeBytes >= 0L) entry.sizeBytes else null
                else -> null
            }
        }
        cursor.addRow(values)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.startsWith("r")) {
            throw FileNotFoundException("StreamDocumentProvider is read-only (mode=$mode)")
        }
        val token = tokenOf(uri) ?: throw FileNotFoundException("bad uri: $uri")
        val entry = StreamDocumentRegistry.get(token)
            ?: throw FileNotFoundException("expired or unknown document: $token")

        // A real local/SAF descriptor is already seekable. Hand it straight through so
        // external viewers get the same kernel-backed I/O path as opening from a file manager.
        // Wrapping local files in openProxyFileDescriptor serializes every read through FUSE
        // and is dramatically slower when a viewer scans or renders the whole document.
        entry.openFileDescriptor?.let { openDirect ->
            return try {
                openDirect()
            } catch (e: Throwable) {
                logcat("StreamDoc", e)
                throw FileNotFoundException(e.message ?: "open failed")
            }
        }

        val openSource = entry.openSource
            ?: throw FileNotFoundException("document has no readable source: $token")
        val context = context ?: throw FileNotFoundException("no context")
        val storage = context.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("StorageManager unavailable")

        val isVideo = VideoDirectLinkByteSource.isVideo(entry.mimeType, entry.displayName)
        val source = try {
            if (isVideo) {
                VideoDirectLinkByteSource.open(
                    openLane = openSource,
                    knownSize = entry.sizeBytes,
                )
            } else {
                val (blockSize, maxBlocks) = BlockCacheArchiveByteSource.forMimeType(
                    mimeType = entry.mimeType,
                    displayName = entry.displayName,
                )
                BlockCacheArchiveByteSource(
                    openSource(),
                    knownSize = entry.sizeBytes,
                    blockSize = blockSize,
                    maxBlocks = maxBlocks,
                )
            }
        } catch (e: Throwable) {
            logcat("StreamDoc", e)
            throw FileNotFoundException(e.message ?: "open failed")
        }
        val size = try {
            source.size.coerceAtLeast(0L)
        } catch (e: Throwable) {
            runCatching { source.close() }
            logcat("StreamDoc", e)
            throw FileNotFoundException(e.message ?: "size failed")
        }
        if (size < 1L) {
            runCatching { source.close() }
            throw FileNotFoundException("empty document")
        }

        val threadName = if (isVideo) "LocalViewer-streamdoc-video" else "LocalViewer-streamdoc"
        val callbackPriority = if (isVideo) {
            Process.THREAD_PRIORITY_FOREGROUND
        } else {
            Process.THREAD_PRIORITY_DEFAULT
        }
        // HandlerThread.run() applies this constructor priority after the looper starts.
        val thread = HandlerThread(threadName, callbackPriority).apply { start() }
        return try {
            val pfd = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                SourceProxyCallback(
                    source,
                    size,
                    thread,
                    token,
                    exactMidFile = true,
                    foregroundIo = isVideo,
                ),
                Handler(thread.looper),
            )
            StreamDocumentRegistry.retain(token)
            pfd
        } catch (e: Throwable) {
            runCatching { source.close() }
            thread.quitSafely()
            logcat("StreamDoc", e)
            throw FileNotFoundException(e.message ?: "proxy fd failed")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val PATH_SEGMENT = "streamdoc"

        fun authority(): String = "${BuildConfig.APPLICATION_ID}.streamdoc"

        /**
         * Grantable streamdoc URI.
         *
         * Shape: `content://…/streamdoc/{token}` or `…/streamdoc/{token}/{displayName}`.
         *
         * [token] is the registry key. Optional [displayName] is the last path segment so
         * external apps show the real file name (many use the URI tail, not
         * [OpenableColumns.DISPLAY_NAME]). Lookup still uses [token] only — one document
         * per token. External **video** uses [ExternalHttpStreamServer] for sidecar subs.
         */
        fun uriFor(token: String, displayName: String? = null): Uri {
            val builder = Uri.Builder()
                .scheme("content")
                .authority(authority())
                .appendPath(PATH_SEGMENT)
                .appendPath(token)
            val name = displayName?.let { sanitizeDisplayNameForPath(it) }
            if (!name.isNullOrEmpty()) {
                builder.appendPath(name)
            }
            return builder.build()
        }

        fun tokenOf(uri: Uri): String? {
            val segs = uri.pathSegments
            // streamdoc/{token} or streamdoc/{token}/{displayName}
            if (segs.size >= 2 && segs[0] == PATH_SEGMENT) return segs[1]
            if (segs.size == 1) return segs[0]
            return null
        }

        /** Single path segment; keep extension for type sniffing. */
        fun sanitizeDisplayNameForPath(displayName: String): String {
            val base = displayName
                .replace('\\', '/')
                .substringAfterLast('/')
                .trim()
                .ifEmpty { return "file" }
            return base
                .replace('/', '_')
                .replace('\u0000', '_')
                .take(180)
                .ifEmpty { "file" }
        }
    }
}

/**
 * Bridges [ArchiveByteSource] to a seekable PFD for external viewers.
 *
 * **FuseAppLoop always Log.e's any thrown [ErrnoException]** (full stack). So we must not
 * throw on transient network blips: fill the exact byte count Android requires, retry with
 * backoff, and only fail after a long deadline. Soft EOF (return 0) for released / past-size.
 *
 * Mid-file short success confuses video players (treat as EOF → exit). Always return exact
 * [want] or throw EIO after the deadline — never a soft partial mid-file.
 */
private class SourceProxyCallback(
    private val source: ArchiveByteSource,
    private val size: Long,
    private val thread: HandlerThread,
    private val token: String,
    private val exactMidFile: Boolean = true,
    foregroundIo: Boolean = false,
) : ProxyFileDescriptorCallback() {
    @Volatile
    private var released = false

    @Volatile
    private var terminalFailure = false

    /**
     * Blocking SMB/WebDAV calls must not occupy the Fuse callback looper indefinitely.
     * Source close cancels CIO and tears down active SMB handles when the deadline expires.
     */
    private val readExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                runCatching {
                    Process.setThreadPriority(
                        if (foregroundIo) {
                            Process.THREAD_PRIORITY_FOREGROUND
                        } else {
                            Process.THREAD_PRIORITY_DEFAULT
                        },
                    )
                }
                runnable.run()
            },
            "LocalViewer-streamdoc-io",
        ).apply { isDaemon = true }
    }

    override fun onGetSize(): Long = size

    @Throws(ErrnoException::class)
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (size <= 0 || released) return 0
        if (terminalFailure) throw ErrnoException("readAt", OsConstants.EIO)
        if (offset < 0L || offset >= this.size) return 0
        val want = minOf(size.toLong(), this.size - offset).toInt()
        if (want <= 0) return 0

        // ProxyFileDescriptorCallback contract: exact [want] bytes unless true EOF.
        var filled = 0
        var attempt = 0
        var lastError: Throwable? = null
        val deadline = System.nanoTime() + MAX_WAIT_NS

        while (filled < want) {
            if (released) return filled // soft EOF on close race (no Fuse E log)
            if (terminalFailure) break
            val absOff = offset + filled
            if (absOff >= this.size) return filled

            val need = want - filled
            try {
                val result = readAtBeforeDeadline(absOff, need, deadline)
                val n = result.count
                when {
                    n > 0 -> {
                        System.arraycopy(result.bytes, 0, data, filled, n)
                        filled += n
                        attempt = 0 // progress → reset backoff
                        continue
                    }
                    n == 0 -> {
                        // Mid-file 0 is often a transient empty range / reconnect race, not EOF.
                        if (absOff >= this.size || filled > 0 && !exactMidFile) return filled
                        lastError = IOException("readAt returned 0 at offset=$absOff need=$need")
                    }
                    else -> {
                        if (released) return filled
                        lastError = IOException("readAt returned -1 at offset=$absOff need=$need")
                    }
                }
            } catch (e: TimeoutException) {
                lastError = e
                abortBlockedRead()
                break
            } catch (e: ErrnoException) {
                // Do not rethrow immediately — Fuse logs every throw as E.
                if (released) return filled
                lastError = e
            } catch (e: Throwable) {
                if (released) return filled
                lastError = e
            }

            if (System.nanoTime() >= deadline) break
            attempt++
            val sleepMs = minOf(
                RETRY_BACKOFF_MS * attempt,
                RETRY_BACKOFF_MAX_MS,
            )
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                if (exactMidFile && filled < want) {
                    throw ErrnoException("readAt", OsConstants.EINTR)
                }
                return filled
            }
        }

        if (filled == want) return want
        if (released) return 0
        // True EOF only when the file ends inside this request.
        if (offset + filled >= this.size && filled > 0) return filled

        // Last resort only — still logged once by FuseAppLoop (unavoidable if we throw).
        // Never soft-return a mid-file partial: players treat short reads as EOF and exit.
        val msg = lastError?.message ?: "I/O error"
        logcat("StreamDoc") {
            "proxy read gave up offset=$offset want=$want filled=$filled after ${MAX_WAIT_NS / 1_000_000}ms: $msg"
        }
        throw ErrnoException("readAt", OsConstants.EIO)
    }

    override fun onRelease() {
        released = true
        readExecutor.shutdownNow()
        runCatching { source.close() }
        thread.quitSafely()
        // Release this FD. The token stays reusable for player seek/rebuffer reopen;
        // the registry ages it out later and stops the keep-alive after a grace period.
        StreamDocumentRegistry.release(token)
    }

    private data class ReadResult(val count: Int, val bytes: ByteArray)

    /** Run one blocking source call with the remaining callback budget. */
    private fun readAtBeforeDeadline(offset: Long, length: Int, deadlineNs: Long): ReadResult {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) throw TimeoutException("proxy read deadline exceeded")
        // Do not let a timed-out worker keep writing into Fuse's callback buffer.
        val bytes = ByteArray(length)
        val future = readExecutor.submit<ReadResult> {
            ReadResult(source.readAt(offset, bytes, 0, length), bytes)
        }
        return try {
            future.get(remainingNs, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun abortBlockedRead() {
        terminalFailure = true
        // close() is intentionally non-blocking for both remote source implementations.
        runCatching { source.close() }
        readExecutor.shutdownNow()
    }

    private companion object {
        /**
         * Cap total stall per onRead so Fuse does not hang forever on a dead share.
         * Long enough for sticky SMB/WebDAV reconnect after an idle disconnect mid-movie
         * (auth + open + first range), without waiting for the full 120s SMB SO timeout.
         */
        const val MAX_WAIT_NS = 30_000_000_000L // 30s
        const val RETRY_BACKOFF_MS = 50L
        const val RETRY_BACKOFF_MAX_MS = 800L
    }
}
