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
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes a random-access [ArchiveByteSource] as a grantable `content://` URI so
 * external PDF apps can open network/local documents without a full download.
 *
 * Uses [StorageManager.openProxyFileDescriptor]: the peer reads with seek; we
 * fulfill via [ArchiveByteSource.readAt] (SMB/WebDAV Range, local pread).
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
        val context = context ?: throw FileNotFoundException("no context")
        val storage = context.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("StorageManager unavailable")

        val source = try {
            entry.openSource()
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
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                SourceProxyCallback(source, size, thread),
                Handler(thread.looper),
            )
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
 * FuseAppLoop logs every thrown [ErrnoException] as **E** (noisy stack traces). Prefer
 * returning **0** (EOF) for closed/out-of-range, and only throw EIO after retries fail
 * on a live source — Drive/Samsung often race reads past release or hit brief SMB glitches.
 */
private class SourceProxyCallback(
    private val source: ArchiveByteSource,
    private val size: Long,
    private val thread: HandlerThread,
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

        var lastError: Throwable? = null
        repeat(READ_ATTEMPTS) { attempt ->
            if (released) return 0
            try {
                val n = source.readAt(offset, data, 0, want)
                when {
                    // EOF or closed mid-session: Fuse treats 0 as end-of-file (no E log).
                    n == 0 -> return 0
                    n < 0 -> {
                        // ArchiveByteSource uses -1 for error *or* closed; if released, soft EOF.
                        if (released) return 0
                        lastError = IOException("readAt returned -1 at offset=$offset want=$want")
                    }
                    else -> return n
                }
            } catch (e: ErrnoException) {
                throw e
            } catch (e: Throwable) {
                if (released) return 0
                lastError = e
            }
            if (attempt < READ_ATTEMPTS - 1) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }
        }
        // True failure after retries — still EIO for the peer, but only log once (no stack spam).
        val msg = lastError?.message ?: "I/O error"
        logcat("StreamDoc") { "proxy read failed after $READ_ATTEMPTS tries offset=$offset: $msg" }
        throw ErrnoException("readAt", OsConstants.EIO)
    }

    override fun onRelease() {
        released = true
        runCatching { source.close() }
        thread.quitSafely()
    }

    private companion object {
        const val READ_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 40L
    }
}
