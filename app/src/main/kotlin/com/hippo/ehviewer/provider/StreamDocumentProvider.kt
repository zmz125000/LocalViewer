package com.hippo.ehviewer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BlockCacheArchiveByteSource
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes a document as a grantable `content://` URI for external viewers.
 *
 * Local/SAF documents return their real seekable descriptor. Network documents use
 * [StorageManager.openProxyFileDescriptor]; the peer seeks and [ArchiveByteSource.readAt]
 * fulfills reads through SMB/WebDAV ranges and a bounded block cache.
 *
 * Not all viewers behave equally well with proxy FDs — some still buffer large
 * spans — but we never stage a complete file first.
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

        val source = try {
            // PDF keeps small sparse blocks; video uses larger blocks / higher cap so
            // sequential playback can sustain high bitrates without a separate readahead path.
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

        val thread = HandlerThread("LocalViewer-streamdoc").apply { start() }
        return try {
            val pfd = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                SourceProxyCallback(source, size, thread, token),
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

        fun uriFor(token: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority())
            .appendPath(PATH_SEGMENT)
            .appendPath(token)
            .build()

        fun tokenOf(uri: Uri): String? {
            val segs = uri.pathSegments
            if (segs.size >= 2 && segs[0] == PATH_SEGMENT) return segs[1]
            if (segs.size == 1) return segs[0]
            return null
        }
    }
}

/**
 * Bridges [ArchiveByteSource] to a seekable PFD for external PDF viewers.
 *
 * **FuseAppLoop always Log.e's any thrown [ErrnoException]** (full stack). So we must not
 * throw on transient network blips: fill the exact byte count Android requires, retry with
 * backoff, and only fail after a long deadline. Soft EOF (return 0) for released / past-size.
 *
 * PDF jump-to-page hammers random offsets; SMB/WebDAV glitches are common and Drive retries
 * EIO — but that floods logcat. Prefer wait-and-succeed over throw.
 */
private class SourceProxyCallback(
    private val source: ArchiveByteSource,
    private val size: Long,
    private val thread: HandlerThread,
    private val token: String,
) : ProxyFileDescriptorCallback() {
    @Volatile
    private var released = false

    override fun onGetSize(): Long = size

    @Throws(ErrnoException::class)
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (size <= 0 || released) return 0
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
            val absOff = offset + filled
            if (absOff >= this.size) return filled

            val need = want - filled
            try {
                val n = source.readAt(absOff, data, filled, need)
                when {
                    n > 0 -> {
                        filled += n
                        attempt = 0 // progress → reset backoff
                        continue
                    }
                    n == 0 -> {
                        // Mid-file 0 is often a transient empty range / reconnect race, not EOF.
                        if (absOff >= this.size || filled > 0) return filled
                        lastError = IOException("readAt returned 0 at offset=$absOff need=$need")
                    }
                    else -> {
                        if (released) return filled
                        lastError = IOException("readAt returned -1 at offset=$absOff need=$need")
                    }
                }
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
                return filled
            }
        }

        if (filled == want) return want
        if (filled > 0) {
            // Partial progress is better than EIO: client re-reads remainder without Fuse E spam.
            return filled
        }
        if (released) return 0

        // Last resort only — still logged once by FuseAppLoop (unavoidable if we throw).
        val msg = lastError?.message ?: "I/O error"
        logcat("StreamDoc") {
            "proxy read gave up offset=$offset want=$want after ${MAX_WAIT_NS / 1_000_000}ms: $msg"
        }
        throw ErrnoException("readAt", OsConstants.EIO)
    }

    override fun onRelease() {
        released = true
        runCatching { source.close() }
        thread.quitSafely()
        // Drop token when the last proxy FD for this grant is closed (refcount).
        StreamDocumentRegistry.release(token)
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
