package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Random-access WebDAV archive source for stream open (HTTP Range).
 * Same [ReadAheadArchiveByteSource] windowing as SMB; each miss is one Range GET.
 */
class WebDavArchiveByteSource(
    source: WebDavSourceEntity,
    password: String,
    remoteRelativeFile: String,
    /** Solid fake-stream: large sequential windows + pipeline (see SMB twin). */
    preferSequential: Boolean = false,
) : ArchiveByteSource {
    private val inner = ReadAheadArchiveByteSource(
        inner = RawWebDavArchiveByteSource(source, password, remoteRelativeFile),
        preferSequential = preferSequential,
        pipeline = true,
    )

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
        inner.readAt(offset, buf, off, len)

    override fun warm(offset: Long, length: Int) = inner.warm(offset, length)

    override fun close() = inner.close()
}

private class RawWebDavArchiveByteSource(
    private val source: WebDavSourceEntity,
    private val password: String,
    remoteRelativeFile: String,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)
    private val sizeRef = AtomicReference<Long?>(null)
    /** Epoch ms until which failed stats fail-fast (avoid readahead hammering a down server). */
    private val failFastUntilMs = AtomicLong(0L)

    /**
     * Resolved archive size. Once known, never re-stats (survives brief server restarts).
     * On failure throws [IOException] (not [IllegalStateException]) so open/read paths
     * can fail soft — never crash the process on a WebDAV blip.
     */
    override val size: Long
        get() {
            sizeRef.get()?.let { return it }
            val now = System.currentTimeMillis()
            if (now < failFastUntilMs.get()) {
                throw IOException("Cannot stat WebDAV archive (recent fail): $remote")
            }
            val s = resolveSizeWithRetry()
            if (s != null && s > 0L) {
                sizeRef.compareAndSet(null, s)
                return sizeRef.get() ?: s
            }
            failFastUntilMs.set(now + FAIL_FAST_MS)
            throw IOException("Cannot stat WebDAV archive: $remote")
        }

    /**
     * Brief multi-try for server restart windows (most restarts recover within ~1–2 s).
     * Returns null only after all attempts fail.
     */
    private fun resolveSizeWithRetry(): Long? = runBlocking {
        var last: Long? = null
        repeat(SIZE_ATTEMPTS) { attempt ->
            last = WebDavClient.fileSizeOrNull(source, password, remote)
            if (last != null && last!! > 0L) return@runBlocking last
            if (attempt < SIZE_ATTEMPTS - 1) {
                delay(SIZE_BACKOFF_MS * (attempt + 1))
            }
        }
        last?.takeIf { it > 0L }
    }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        return try {
            val fileSize = size
            if (offset >= fileSize) return 0
            val toRead = minOf(len.toLong(), fileSize - offset).toInt()
            runBlocking {
                WebDavClient.readRange(source, password, remote, offset, buf, off, toRead)
            }
        } catch (e: Throwable) {
            logcat("WebDavArchive", e)
            -1
        }
    }

    override fun close() = Unit

    private companion object {
        const val SIZE_ATTEMPTS = 3
        const val SIZE_BACKOFF_MS = 250L
        /** After a full failed stat, skip re-HEAD for this long (readahead / JNI thrash). */
        const val FAIL_FAST_MS = 1_500L
    }
}
