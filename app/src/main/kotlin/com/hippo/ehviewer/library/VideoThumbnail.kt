package com.hippo.ehviewer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath
import splitties.init.appCtx

sealed interface VideoThumbnailSource {
    val cacheIdentity: String
    val isNetwork: Boolean

    data class Local(val path: String) : VideoThumbnailSource {
        override val isNetwork: Boolean = false
        override val cacheIdentity: String
            get() {
                val file = File(path)
                return "local:$path:${file.length()}:${file.lastModified()}"
            }
    }

    data class Smb(val sourceId: Long, val remoteRelativeFile: String) : VideoThumbnailSource {
        override val isNetwork: Boolean = true
        override val cacheIdentity = "smb:$sourceId:$remoteRelativeFile"
    }

    data class WebDav(val sourceId: Long, val remoteRelativeFile: String) : VideoThumbnailSource {
        override val isNetwork: Boolean = true
        override val cacheIdentity = "webdav:$sourceId:$remoteRelativeFile"
    }
}

/**
 * Lazy, single-lane video frame extraction for visible browse rows.
 *
 * Disk layout: `cache/video_thumb_cache/` under the app data dir — same parent as
 * `smb_thumb_cache` / `archive_thumb`. Byte budget + LRU trim is shared via
 * [OriginDiskCache] ([OriginDiskCache.THUMB_BUDGET_BYTES]).
 *
 * Local videos always extract into this cache. Network (SMB/WebDAV) extraction is
 * gated by [Settings.downloadNetworkVideoThumbs]; cached JPEGs still show when off.
 */
object VideoThumbnail {
    /** Long edge — matches [OriginDiskCache.THUMB_EDGE] (other browse covers). */
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    /**
     * Prefer a mid-intro sync frame so black/logo openers are less common.
     * Fallback [0] if 5s is past EOF or not decodable.
     *
     * Network cost: one-time range reads to find a keyframe (cached afterward).
     * 5s is usually a bit more I/O than t=0 but still far smaller than a full file download
     * because [MediaMetadataRetriever] seeks with [OPTION_CLOSEST_SYNC] and [ArchiveByteSource]
     * only serves the ranges requested.
     */
    private const val FRAME_TIME_US = 5_000_000L
    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private val extractMutex = Mutex()

    /** Shared with [OriginDiskCache.trimThumbs] (`cache/video_thumb_cache`). */
    fun cacheDirectory(): File = File(appCtx.applicationInfo.dataDir, "cache/video_thumb_cache").apply { mkdirs() }

    @Suppress("UNUSED_PARAMETER")
    suspend fun getOrCreate(context: Context, source: VideoThumbnailSource): File? = withIOContext {
        // [context] kept for call-site API stability; files live under app dataDir.
        val directory = cacheDirectory()
        val cacheKey = sha256(source.cacheIdentity)
        val target = File(directory, "$cacheKey.jpg")
        val failure = File(directory, "$cacheKey.failed")
        if (failure.isFile && System.currentTimeMillis() - failure.lastModified() < FAILURE_RETRY_MS) {
            return@withIOContext null
        }
        if (isFresh(target, source)) return@withIOContext target

        // Network extract disabled: still serve disk hits above; do not open SMB/WebDAV.
        if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
            return@withIOContext null
        }

        extractMutex.withLock {
            if (isFresh(target, source)) return@withLock target
            if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
                return@withLock null
            }
            val byteSource = try {
                openSource(source)
            } catch (_: Throwable) {
                failure.writeText("")
                return@withLock null
            }
            byteSource.use {
                val frame = extractFrame(it) ?: run {
                    failure.writeText("")
                    return@withLock null
                }
                val scaled = scale(frame)
                try {
                    val temporary = File(directory, target.name + ".tmp")
                    val written = temporary.outputStream().buffered().use { output ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    }
                    if (!written || !temporary.renameTo(target)) {
                        temporary.delete()
                        return@withLock null
                    }
                    failure.delete()
                    // Byte-budget trim (shared with other thumb folders).
                    OriginDiskCache.scheduleTrim()
                } finally {
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                }
            }
            target
        }
    }

    private fun isFresh(target: File, source: VideoThumbnailSource): Boolean {
        if (!target.isFile || target.length() <= 0L) return false
        // Local identity already includes size+mtime; file presence is enough.
        if (!source.isNetwork) return true
        return System.currentTimeMillis() - target.lastModified() < REMOTE_CACHE_MAX_AGE_MS
    }

    private suspend fun openSource(source: VideoThumbnailSource): ArchiveByteSource = when (source) {
        is VideoThumbnailSource.Local -> {
            val file = File(source.path)
            val pfd = if (source.path.startsWith('/') && file.isFile) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                source.path.toPath().openFileDescriptor("r")
            }
            PfdArchiveByteSource(pfd)
        }
        is VideoThumbnailSource.Smb -> {
            val smb = SmbRepository.load(source.sourceId) ?: error("SMB source missing")
            SmbArchiveByteSource(
                source = smb,
                password = SmbPasswordStore.get(source.sourceId),
                remoteRelativeFile = source.remoteRelativeFile,
                pipeline = false,
                readahead = false,
            )
        }
        is VideoThumbnailSource.WebDav -> {
            val webDav = WebDavRepository.load(source.sourceId) ?: error("WebDAV source missing")
            WebDavArchiveByteSource(
                source = webDav,
                password = WebDavPasswordStore.get(source.sourceId),
                remoteRelativeFile = source.remoteRelativeFile,
                pipeline = false,
                readahead = false,
            )
        }
    }

    private fun extractFrame(source: ArchiveByteSource): Bitmap? {
        val dataSource = ArchiveMediaDataSource(source)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(dataSource)
            retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            dataSource.close()
        }
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val edge = EDGE_PX
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= edge) return bitmap
        val factor = edge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private class ArchiveMediaDataSource(private val source: ArchiveByteSource) : MediaDataSource() {
    private val closed = AtomicBoolean(false)

    override fun getSize(): Long = source.size

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get()) return -1
        val read = source.readAt(position, buffer, offset, size)
        return if (read <= 0) -1 else read
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { source.close() }
    }
}
