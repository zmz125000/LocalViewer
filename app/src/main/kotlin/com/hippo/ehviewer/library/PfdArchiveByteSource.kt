package com.hippo.ehviewer.library

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

/**
 * Random-access [ArchiveByteSource] over a [ParcelFileDescriptor]
 * (real files and SAF `content://` tree documents).
 *
 * Android FUSE (SAF, sdcardfs, AppFuse proxy descriptors) often rejects a single
 * multi-megabyte `pread` with EINVAL/EIO. Image-PDF streams are typically several
 * MiB, so reads are issued in FUSE-sized chunks and transient errors are retried.
 */
class PfdArchiveByteSource(
    private val pfd: ParcelFileDescriptor,
    private val ownsPfd: Boolean = true,
) : ArchiveByteSource {
    override val size: Long = pfd.statSize.coerceAtLeast(0L)

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (off < 0 || len > buf.size - off) {
            throw IOException("pread dest out of range off=$off len=$len buf=${buf.size}")
        }
        if (size <= 0L || offset < 0L || offset >= size) return 0
        val want = minOf(len.toLong(), size - offset).toInt()
        var got = 0
        var chunkCap = PREAD_CHUNK
        while (got < want) {
            val chunk = minOf(want - got, chunkCap)
            val n = try {
                preadChunk(offset + got, buf, off + got, chunk)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINVAL && chunkCap > FUSE_MAX_READ) {
                    chunkCap = FUSE_MAX_READ
                    continue
                }
                throw IOException(
                    "pread failed at ${offset + got} (errno=${e.errno})",
                    e,
                )
            }
            if (n == 0) break
            if (n < 0) {
                throw IOException("pread failed at ${offset + got}")
            }
            got += n
        }
        return got
    }

    private fun preadChunk(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        var attempt = 0
        var last: ErrnoException? = null
        while (attempt < MAX_RETRIES) {
            try {
                val n = Os.pread(pfd.fileDescriptor, buf, off, len, offset)
                return if (n < 0) -1 else n
            } catch (e: ErrnoException) {
                last = e
                when (e.errno) {
                    OsConstants.EINTR, OsConstants.EAGAIN -> {
                        attempt++
                        continue
                    }
                    OsConstants.EIO, OsConstants.ENOMEM -> {
                        attempt++
                        if (attempt >= MAX_RETRIES) break
                        try {
                            Thread.sleep(RETRY_SLEEP_MS * attempt)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    else -> throw e
                }
            }
        }
        throw last ?: ErrnoException("pread", OsConstants.EIO)
    }

    override fun close() {
        if (ownsPfd) runCatching { pfd.close() }
    }

    private companion object {
        /** AppFuse / many Android FUSE mounts cap one READ at 128 KiB. */
        const val FUSE_MAX_READ = 128 * 1024
        const val PREAD_CHUNK = FUSE_MAX_READ
        const val MAX_RETRIES = 4
        const val RETRY_SLEEP_MS = 20L
    }
}
