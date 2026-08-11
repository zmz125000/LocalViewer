package com.hippo.ehviewer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * Lazy video frame extraction for visible browse rows, limited to four concurrent decodes.
 *
 * Disk layout: `cache/video_thumb_cache/` under the app data dir — same parent as
 * `smb_thumb_cache` / `archive_thumb`. Byte budget + LRU trim is shared via
 * [OriginDiskCache] ([OriginDiskCache.THUMB_BUDGET_BYTES]).
 *
 * Local videos always extract into this cache. Network (SMB/WebDAV) extraction is
 * gated by [Settings.downloadNetworkVideoThumbs]; cached JPEGs still show when off.
 *
 * Network thumbnails stream only a bounded prefix through the same browse-thumbnail
 * pools as folder/gallery covers. They never use StreamDocumentProvider, loopback HTTP,
 * sticky player sessions, or random network seeks.
 */
object VideoThumbnail {
    /** Long edge — matches [OriginDiskCache.THUMB_EDGE] (other browse covers). */
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    /** Keep four decoders, but cap each network stream so playback keeps bandwidth. */
    private const val MAX_NETWORK_PREFIX_BYTES = 4L * 1024L * 1024L
    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_CONCURRENT_EXTRACTIONS = 4
    private val extractSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

    /** Shared with [OriginDiskCache.trimThumbs] (`cache/video_thumb_cache`). */
    fun cacheDirectory(): File = File(appCtx.applicationInfo.dataDir, "cache/video_thumb_cache").apply { mkdirs() }

    /**
     * Disk-hit only (no extract). Used by [HistoryThumbKey] so history covers paint
     * when browse already extracted a frame.
     */
    fun cachedJpegIfPresent(source: VideoThumbnailSource): File? {
        val directory = cacheDirectory()
        val cacheKey = sha256(source.cacheIdentity)
        val target = File(directory, "$cacheKey.jpg")
        return if (isFresh(target, source)) target else null
    }

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

        extractSemaphore.withPermit {
            if (isFresh(target, source)) return@withPermit target
            if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
                return@withPermit null
            }
            val frame = try {
                extractThumbnailFrame(source, directory, cacheKey)
            } catch (_: Throwable) {
                failure.writeText("")
                return@withPermit null
            }
            if (frame == null) {
                failure.writeText("")
                return@withPermit null
            }
            val scaled = scale(frame)
            try {
                val temporary = File(directory, target.name + ".tmp." + System.nanoTime())
                val written = temporary.outputStream().buffered().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
                }
                if (!written || !temporary.renameTo(target)) {
                    temporary.delete()
                    return@withPermit null
                }
                failure.delete()
                // Byte-budget trim (shared with other thumb folders).
                OriginDiskCache.scheduleTrim()
            } finally {
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
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

    private suspend fun extractThumbnailFrame(
        source: VideoThumbnailSource,
        directory: File,
        cacheKey: String,
    ): Bitmap? = when (source) {
        is VideoThumbnailSource.Local -> {
            val file = File(source.path)
            val pfd = if (source.path.startsWith('/') && file.isFile) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                source.path.toPath().openFileDescriptor("r")
            }
            extractFrame(PfdArchiveByteSource(pfd))
        }
        is VideoThumbnailSource.Smb -> {
            val smb = SmbRepository.load(source.sourceId) ?: error("SMB source missing")
            extractNetworkThumbnailFrame(directory, cacheKey, source.remoteRelativeFile) { temporary ->
                SmbCache.withBrowseThumbFetchSlot {
                    SmbGateway.downloadFilePrefix(
                        source = smb,
                        password = SmbPasswordStore.get(source.sourceId),
                        relativeFilePath = source.remoteRelativeFile,
                        destination = temporary,
                        maxBytes = MAX_NETWORK_PREFIX_BYTES,
                    )
                }
            }
        }
        is VideoThumbnailSource.WebDav -> {
            val webDav = WebDavRepository.load(source.sourceId) ?: error("WebDAV source missing")
            extractNetworkThumbnailFrame(directory, cacheKey, source.remoteRelativeFile) { temporary ->
                WebDavCache.withBrowseThumbFetchSlot {
                    WebDavClient.downloadFilePrefix(
                        source = webDav,
                        password = WebDavPasswordStore.get(source.sourceId),
                        relativeFilePath = source.remoteRelativeFile,
                        destination = temporary,
                        maxBytes = MAX_NETWORK_PREFIX_BYTES,
                    )
                }
            }
        }
    }

    private suspend fun extractNetworkThumbnailFrame(
        directory: File,
        cacheKey: String,
        remoteFileName: String,
        download: suspend (File) -> Long,
    ): Bitmap? {
        val extension = remoteFileName
            .substringAfterLast('.', "video")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifEmpty { "video" }
        val temporary = File(
            directory,
            "$cacheKey.tmp.${System.nanoTime()}.$extension",
        )
        return try {
            val downloaded = download(temporary)
            if (downloaded <= 0L || temporary.length() <= 0L) {
                null
            } else {
                extractFrame(temporary)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun extractFrame(source: ArchiveByteSource): Bitmap? {
        val dataSource = ArchiveMediaDataSource(source)
        return try {
            decodeThumbnailFrame { it.setDataSource(dataSource) }
        } finally {
            dataSource.close()
        }
    }

    private fun extractFrame(file: File): Bitmap? = decodeThumbnailFrame { it.setDataSource(file.absolutePath) }

    private inline fun decodeThumbnailFrame(
        setDataSource: (MediaMetadataRetriever) -> Unit,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever)
            // Let Android choose the representative thumbnail frame. A time-zero sync
            // frame remains the fallback for truncated prefixes whose container metadata
            // cannot expose a representative frame.
            retriever.getFrameAtTime()
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
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
