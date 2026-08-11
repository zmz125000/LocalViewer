package com.hippo.ehviewer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * Network thumbnails expose the complete remote file as a seekable byte source through the
 * same browse-thumbnail pools as folder/gallery covers. Decoder reads use pooled SMB handles
 * or WebDAV ranges; they never use StreamDocumentProvider, loopback HTTP, or sticky sessions.
 */
object VideoThumbnail {
    /** Long edge — matches [OriginDiskCache.THUMB_EDGE] (other browse covers). */
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    /** Keep four decoders; network work is additionally capped by each browse-thumb pool. */
    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val FAILURE_MARKER_DECODE = "decode-head-tail"
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_CONCURRENT_EXTRACTIONS = 4
    private const val SAMPLE_GRID = 12
    private const val BLACK_LUMA = 24
    private const val MIN_VISIBLE_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 15 / 100
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
        if (failure.isFile) {
            val isCurrentDecodeFailure =
                runCatching { failure.readText() == FAILURE_MARKER_DECODE }.getOrDefault(false)
            if (
                isCurrentDecodeFailure &&
                System.currentTimeMillis() - failure.lastModified() < FAILURE_RETRY_MS
            ) {
                return@withIOContext null
            }
            // Blank legacy markers could have been written by a cancelled network fetch.
            failure.delete()
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
                extractThumbnailFrame(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Transport and source errors are transient; retry when the row is visible again.
                return@withPermit null
            }
            if (frame == null) {
                // Avoid repeatedly downloading formats/prefixes Android cannot decode.
                failure.writeText(FAILURE_MARKER_DECODE)
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

    private suspend fun extractThumbnailFrame(source: VideoThumbnailSource): Bitmap? = when (source) {
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
            SmbCache.withBrowseThumbFetchSlot {
                extractNetworkFrame(
                    SmbArchiveByteSource(
                        source = smb,
                        password = SmbPasswordStore.get(source.sourceId),
                        remoteRelativeFile = source.remoteRelativeFile,
                        pipeline = false,
                        readahead = false,
                    ),
                )
            }
        }
        is VideoThumbnailSource.WebDav -> {
            val webDav = WebDavRepository.load(source.sourceId) ?: error("WebDAV source missing")
            WebDavCache.withBrowseThumbFetchSlot {
                extractNetworkFrame(
                    WebDavArchiveByteSource(
                        source = webDav,
                        password = WebDavPasswordStore.get(source.sourceId),
                        remoteRelativeFile = source.remoteRelativeFile,
                        pipeline = false,
                        readahead = false,
                    ),
                )
            }
        }
    }

    /**
     * Close an in-flight random-access source as soon as its browse row is cancelled.
     * Both implementations use close to interrupt blocked SMB/WebDAV reads.
     */
    private suspend fun extractNetworkFrame(source: ArchiveByteSource): Bitmap? = suspendCancellableCoroutine { continuation ->
        // Registration happens before the blocking retriever call, so scrolling away
        // closes the active handle/range source from the cancelling thread.
        continuation.invokeOnCancellation { source.close() }
        val result = runCatching { extractFrame(source) }
        source.close()
        if (continuation.isActive) {
            continuation.resumeWith(result)
        } else {
            result.getOrNull()?.recycle()
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
    ): Bitmap? = decodeFrame(setDataSource) { selectThumbnailFrame() }

    /**
     * Android's representative frame is normally fast and useful. Only a null/mostly-black
     * result causes extra seeks, first near one-third and then two-thirds of the duration.
     */
    private fun MediaMetadataRetriever.selectThumbnailFrame(): Bitmap? {
        var best = runCatching {
            embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
        var bestScore = best?.let(::visibleSampleCount) ?: -1
        if (bestScore >= MIN_VISIBLE_SAMPLES) return best

        runCatching { getFrameAtTime() }.getOrNull()?.let { representative ->
            val score = visibleSampleCount(representative)
            if (score > bestScore) {
                best?.recycle()
                best = representative
                bestScore = score
            } else {
                representative.recycle()
            }
        }
        if (bestScore >= MIN_VISIBLE_SAMPLES) return best

        val durationUs = runCatching {
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.times(1_000L)
        }.getOrNull()
        val lastUs = durationUs?.minus(1L)?.coerceAtLeast(0L)
        val candidateTimes = buildList {
            if (durationUs != null) {
                add(durationUs / 3L)
                add(durationUs * 2L / 3L)
            }
            add(if (lastUs == null) 5_000_000L else minOf(5_000_000L, lastUs))
            add(0L)
        }.distinct()

        for (timeUs in candidateTimes) {
            val candidate = runCatching {
                getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: continue
            val score = visibleSampleCount(candidate)
            if (score > bestScore) {
                best?.recycle()
                best = candidate
                bestScore = score
            } else {
                candidate.recycle()
            }
            if (bestScore >= MIN_VISIBLE_SAMPLES) break
        }
        return best
    }

    /** Count sampled pixels that are visibly above black without allocating another bitmap. */
    private fun visibleSampleCount(bitmap: Bitmap): Int = runCatching {
        var visible = 0
        for (sampleY in 0 until SAMPLE_GRID) {
            val y = (sampleY * bitmap.height / SAMPLE_GRID).coerceAtMost(bitmap.height - 1)
            for (sampleX in 0 until SAMPLE_GRID) {
                val x = (sampleX * bitmap.width / SAMPLE_GRID).coerceAtMost(bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                val luma = (
                    77 * android.graphics.Color.red(color) +
                        150 * android.graphics.Color.green(color) +
                        29 * android.graphics.Color.blue(color)
                    ) shr 8
                if (luma >= BLACK_LUMA) visible++
            }
        }
        visible
    }.getOrDefault(MIN_VISIBLE_SAMPLES)

    private inline fun decodeFrame(
        setDataSource: (MediaMetadataRetriever) -> Unit,
        getFrame: MediaMetadataRetriever.() -> Bitmap?,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever)
            getFrame(retriever)
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
