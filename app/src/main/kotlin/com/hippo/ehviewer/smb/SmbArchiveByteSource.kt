package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Blocking random-access reads of one remote SMB file for stream archive open.
 * Uses [SmbGateway.readRange]; safe to call from native/archive IO threads.
 */
class SmbArchiveByteSource(
    private val source: SmbSourceEntity,
    private val password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val sizeRef = AtomicReference<Long?>(null)

    override val size: Long
        get() {
            sizeRef.get()?.let { return it }
            val s = runBlocking {
                SmbGateway.fileSizeOrNull(source, password, remote)
            } ?: error("Cannot stat SMB archive: $remote")
            sizeRef.set(s)
            return s
        }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (offset >= size) return 0
        val toRead = minOf(len.toLong(), size - offset).toInt()
        return try {
            runBlocking {
                SmbGateway.readRange(source, password, remote, offset, buf, off, toRead)
            }
        } catch (e: Throwable) {
            logcat(e)
            -1
        }
    }

    override fun close() = Unit
}
