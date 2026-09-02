package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.FileArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipMemberCover
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * Random-access WebDAV archive source for stream open (HTTP Range).
 * Same [ReadAheadArchiveByteSource] windowing as SMB; each miss is one Range GET.
 *
 * @param stickySession Use [WebDavClient] sticky CIO client (survives app ON_STOP).
 *   Required for external FUSE PDF so ranging keeps working after LocalViewer backgrounds.
 */
class WebDavArchiveByteSource(
    source: WebDavSourceEntity,
    password: String,
    remoteRelativeFile: String,
    /** Solid / TAR chunk: fixed sequential windows (see SMB twin). */
    preferSequential: Boolean = false,
    /** Pipeline next fixed window (reader). Off for cover thumbs. */
    pipeline: Boolean = true,
    /** Fixed window size (default 8 MiB). */
    sequentialWindow: Int = ReadAheadArchiveByteSource.SEQUENTIAL_WINDOW,
    stickySession: Boolean = false,
    /**
     * When known (e.g. external PDF registration HEAD), skip a second size probe
     * on first [readAt]. Must match the remote file.
     */
    knownSize: Long = -1L,
    /**
     * Windowed readahead for sequential archive parsing. Off when a higher layer
     * (e.g. [com.hippo.ehviewer.library.BlockCacheArchiveByteSource]) owns caching.
     */
    readahead: Boolean = true,
) : ArchiveByteSource {
    private val inner: ArchiveByteSource = run {
        val zipMember = ZipAsDirListing.zipMemberPath(remoteRelativeFile)
        if (zipMember != null) {
            val (zipRel, memberRel) = zipMember
            val local = ZipMemberCover.ensure("webdav:${source.id}:$zipRel", memberRel) {
                WebDavArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = zipRel,
                    pipeline = false,
                    stickySession = stickySession,
                    readahead = true,
                )
            } ?: throw IOException("Cannot extract ZIP member $memberRel from $zipRel")
            FileArchiveByteSource(java.io.File(local.toString()))
        } else {
            val raw = RawWebDavArchiveByteSource(
                source,
                password,
                remoteRelativeFile,
                stickySession,
                knownSize,
            )
            if (readahead) {
                ReadAheadArchiveByteSource(
                    inner = raw,
                    sequentialWindow = sequentialWindow,
                    preferSequential = preferSequential,
                    pipeline = pipeline,
                )
            } else {
                raw
            }
        }
    }

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = inner.readAt(offset, buf, off, len)

    override fun warm(offset: Long, length: Int) = inner.warm(offset, length)

    override fun close() = inner.close()
}

private class RawWebDavArchiveByteSource(
    private val source: WebDavSourceEntity,
    private val password: String,
    remoteRelativeFile: String,
    private val stickySession: Boolean = false,
    knownSize: Long = -1L,
) : ArchiveByteSource {
    private val remote = RemoteArchiveOpen.normalizeRemoteRelative(remoteRelativeFile)

    /** Cached size; ≤0 means unknown. AtomicLong avoids identity-equality issues with Long boxes. */
    private val sizeBytes = AtomicLong(if (knownSize > 0L) knownSize else 0L)

    /** Epoch ms until which failed stats fail-fast (avoid readahead hammering a down server). */
    private val failFastUntilMs = AtomicLong(0L)

    private val closed = AtomicBoolean(false)

    /**
     * All in-flight CIO coroutines (size HEAD/GET and Range reads, including readahead).
     * A single [AtomicReference] would drop concurrent jobs; close must cancel every one.
     */
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()

    /**
     * Resolved archive size. Once known, never re-stats (survives brief server restarts).
     * On failure throws [IOException] (not [IllegalStateException]) so open/read paths
     * can fail soft — never crash the process on a WebDAV blip.
     */
    override val size: Long
        get() {
            if (closed.get()) {
                throw IOException("WebDAV archive source closed: $remote")
            }
            val cached = sizeBytes.get()
            if (cached > 0L) return cached
            val now = System.currentTimeMillis()
            if (now < failFastUntilMs.get()) {
                throw IOException("Cannot stat WebDAV archive (recent fail): $remote")
            }
            val s = resolveSizeWithRetry()
            if (closed.get()) {
                throw IOException("WebDAV archive source closed: $remote")
            }
            if (s != null && s > 0L) {
                sizeBytes.compareAndSet(0L, s)
                val after = sizeBytes.get()
                return if (after > 0L) after else s
            }
            failFastUntilMs.set(now + FAIL_FAST_MS)
            throw IOException("Cannot stat WebDAV archive: $remote")
        }

    /**
     * Brief multi-try for server restart windows (most restarts recover within ~1–2 s).
     * Returns null only after all attempts fail (or [close] cancelled the work).
     */
    private fun resolveSizeWithRetry(): Long? = try {
        withTrackedJob {
            var last: Long? = null
            repeat(SIZE_ATTEMPTS) { attempt ->
                if (closed.get()) return@withTrackedJob null
                val size = WebDavClient.fileSizeOrNull(source, password, remote, sticky = stickySession)
                last = size
                if (size != null && size > 0L) return@withTrackedJob size
                if (attempt < SIZE_ATTEMPTS - 1) {
                    delay(SIZE_BACKOFF_MS * (attempt + 1))
                }
            }
            last?.takeIf { it > 0L }
        }
    } catch (_: CancellationException) {
        null
    } catch (e: Throwable) {
        if (closed.get()) {
            null
        } else {
            logcat("WebDavArchive", e)
            null
        }
    }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (closed.get()) return -1
        return try {
            val fileSize = size
            if (offset >= fileSize) return 0
            val toRead = minOf(len.toLong(), fileSize - offset).toInt()
            withTrackedJob {
                if (closed.get()) return@withTrackedJob -1
                WebDavClient.readRange(
                    source,
                    password,
                    remote,
                    offset,
                    buf,
                    off,
                    toRead,
                    sticky = stickySession,
                )
            }
        } catch (e: RemoteRangeNotSupportedException) {
            // Permanent capability failure must not be masked as EOF/-1.
            throw e
        } catch (e: CancellationException) {
            -1
        } catch (e: Throwable) {
            if (closed.get()) return -1
            logcat("WebDavArchive", e)
            -1
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Snapshot then cancel — concurrent withTrackedJob finally-removes are fine.
        val jobs = activeJobs.toTypedArray()
        activeJobs.clear()
        for (job in jobs) {
            job.cancel()
        }
    }

    /**
     * Run [block] under [runBlocking], tracking the coroutine [Job] so [close] can
     * cancel size resolution and overlapping Range reads (foreground + readahead).
     */
    private fun <T> withTrackedJob(block: suspend () -> T): T {
        if (closed.get()) throw CancellationException("WebDAV archive source closed")
        return runBlocking {
            val job = currentCoroutineContext().job
            activeJobs.add(job)
            try {
                if (closed.get()) throw CancellationException("WebDAV archive source closed")
                block()
            } finally {
                activeJobs.remove(job)
            }
        }
    }

    private companion object {
        const val SIZE_ATTEMPTS = 3
        const val SIZE_BACKOFF_MS = 250L

        /** After a full failed stat, skip re-HEAD for this long (readahead / JNI thrash). */
        const val FAIL_FAST_MS = 1_500L
    }
}
