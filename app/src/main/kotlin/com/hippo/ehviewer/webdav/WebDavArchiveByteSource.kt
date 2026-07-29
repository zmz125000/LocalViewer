package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Blocking HTTP Range reads for one remote WebDAV archive (stream open).
 *
 * Wraps a raw range source in [ReadAheadArchiveByteSource] so sequential libarchive
 * chunks coalesce into 2 MiB Range GETs instead of one request per 256 KiB.
 */
class WebDavArchiveByteSource(
    source: WebDavSourceEntity,
    password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val inner = ReadAheadArchiveByteSource(
        RawWebDavArchiveByteSource(source, password, remoteRelativeFile),
    )

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
        inner.readAt(offset, buf, off, len)

    override fun close() = inner.close()
}

private class RawWebDavArchiveByteSource(
    private val source: WebDavSourceEntity,
    private val password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val sizeRef = AtomicReference<Long?>(null)

    override val size: Long
        get() {
            sizeRef.get()?.let { return it }
            val s = runBlocking {
                WebDavClient.fileSizeOrNull(source, password, remote)
            } ?: error("Cannot stat WebDAV archive: $remote")
            sizeRef.set(s)
            return s
        }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (offset >= size) return 0
        val toRead = minOf(len.toLong(), size - offset).toInt()
        return try {
            runBlocking {
                WebDavClient.readRange(source, password, remote, offset, buf, off, toRead)
            }
        } catch (e: Throwable) {
            logcat(e)
            -1
        }
    }

    override fun close() = Unit
}
