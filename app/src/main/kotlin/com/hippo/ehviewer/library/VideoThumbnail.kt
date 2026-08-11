package com.hippo.ehviewer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.withIOContext
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

sealed interface VideoThumbnailSource {
    val cacheIdentity: String

    data class Local(val path: String) : VideoThumbnailSource {
        override val cacheIdentity: String
            get() {
                val file = File(path)
                return "local:$path:${file.length()}:${file.lastModified()}"
            }
    }

    data class Smb(val sourceId: Long, val remoteRelativeFile: String) : VideoThumbnailSource {
        override val cacheIdentity = "smb:$sourceId:$remoteRelativeFile"
    }

    data class WebDav(val sourceId: Long, val remoteRelativeFile: String) : VideoThumbnailSource {
        override val cacheIdentity = "webdav:$sourceId:$remoteRelativeFile"
    }
}

/** Lazy, single-lane video frame extraction for visible browse rows. */
object VideoThumbnail {
    private const val EDGE_PX = 480
    private const val FRAME_TIME_US = 5_000_000L
    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val MAX_CACHE_FILES = 512
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private val extractMutex = Mutex()

    suspend fun getOrCreate(context: Context, source: VideoThumbnailSource): File? = withIOContext {
        val directory = File(context.cacheDir, "video_thumbs").apply { mkdirs() }
        val cacheKey = sha256(source.cacheIdentity)
        val target = File(directory, "$cacheKey.jpg")
        val failure = File(directory, "$cacheKey.failed")
        if (failure.isFile && System.currentTimeMillis() - failure.lastModified() < FAILURE_RETRY_MS) {
            return@withIOContext null
        }
        val fresh = target.isFile && target.length() > 0L && (
            source is VideoThumbnailSource.Local ||
                System.currentTimeMillis() - target.lastModified() < REMOTE_CACHE_MAX_AGE_MS
        )
        if (fresh) return@withIOContext target

        extractMutex.withLock {
            val stillFresh = target.isFile && target.length() > 0L && (
                source is VideoThumbnailSource.Local ||
                    System.currentTimeMillis() - target.lastModified() < REMOTE_CACHE_MAX_AGE_MS
            )
            if (stillFresh) {
                return@withLock target
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
                    prune(directory, target)
                } finally {
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                }
            }
            target
        }
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
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= EDGE_PX) return bitmap
        val factor = EDGE_PX.toFloat() / largest
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

    private fun prune(directory: File, keep: File) {
        val files = directory.listFiles { file -> file.extension == "jpg" } ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.asSequence()
            .filterNot { it == keep }
            .sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHE_FILES)
            .forEach { it.delete() }
    }
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
