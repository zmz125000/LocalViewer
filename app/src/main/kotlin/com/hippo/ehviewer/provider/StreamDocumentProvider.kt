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
        val row = Array(columns.size) { idx ->
            when (columns[idx]) {
                OpenableColumns.DISPLAY_NAME -> entry.displayName
                // Opening the source for size can be slow on network; name is enough for choosers.
                OpenableColumns.SIZE -> null
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
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

/** Bridges [ArchiveByteSource] to a seekable PFD for external PDF viewers. */
private class SourceProxyCallback(
    private val source: ArchiveByteSource,
    private val size: Long,
    private val thread: HandlerThread,
) : ProxyFileDescriptorCallback() {
    override fun onGetSize(): Long = size

    @Throws(ErrnoException::class)
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (size <= 0) return 0
        if (offset < 0L || offset >= this.size) return 0
        return try {
            val n = source.readAt(offset, data, 0, size)
            if (n < 0) throw ErrnoException("readAt", OsConstants.EIO)
            n
        } catch (e: ErrnoException) {
            throw e
        } catch (e: IOException) {
            logcat("StreamDoc", e)
            throw ErrnoException("readAt", OsConstants.EIO)
        } catch (e: Throwable) {
            logcat("StreamDoc", e)
            throw ErrnoException("readAt", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        runCatching { source.close() }
        thread.quitSafely()
    }
}
