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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
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
 * Lazy video frame extraction for visible browse rows.
 *
 * Network work matches gallery photo thumbs: browse-host pool only, a shared
 * [SmbCache.withBrowseThumbFetchSlot] / WebDAV twin, and a **bounded** transfer
 * (4 MiB prefix + 2 MiB sparse tail). No sticky sessions, FUSE, or mid-file seeks.
 *
 * Disk: `cache/video_thumb_cache/` — same parent budget as other browse thumbs
 * ([OriginDiskCache.THUMB_BUDGET_BYTES]).
 */
object VideoThumbnail {
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val FAILURE_MARKER_DECODE = "decode-sparse-v4"
    private const val CACHE_FORMAT_VERSION = 4
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_CONCURRENT_EXTRACTIONS = 2
    private const val EXTRACT_TIMEOUT_MS = 12_000L

    /** Enough for a few seconds of 1080p so we can skip a black series opener. */
    private const val MAX_NETWORK_PREFIX_BYTES = 8L * 1024L * 1024L
    private const val MAX_NETWORK_TAIL_BYTES = 2L * 1024L * 1024L
    private const val SAMPLE_GRID = 12
    private const val BLACK_LUMA = 24
    private const val MIN_VISIBLE_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 15 / 100
    private const val MIN_ACCEPT_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 5 / 100

    /** Timestamps still inside a typical 8 MiB prefix. Mid-file seeks hit the sparse hole. */
    private val PREFIX_SEEK_TIMES_US = longArrayOf(
        0L,
        1_000_000L,
        2_000_000L,
        3_000_000L,
        5_000_000L,
        8_000_000L,
    )
    private val extractSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)
    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    fun cacheDirectory(): File = File(appCtx.applicationInfo.dataDir, "cache/video_thumb_cache").apply { mkdirs() }

    fun cachedJpegIfPresent(source: VideoThumbnailSource): File? {
        val directory = cacheDirectory()
        val cacheKey = cacheKey(source)
        val target = File(directory, "$cacheKey.jpg")
        return if (isFresh(target, source)) target else null
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun getOrCreate(context: Context, source: VideoThumbnailSource): File? = withIOContext {
        val directory = cacheDirectory()
        val cacheKey = cacheKey(source)
        val target = File(directory, "$cacheKey.jpg")
        val failure = File(directory, "$cacheKey.failed")
        if (shouldSkipFailed(failure)) return@withIOContext null
        if (isFresh(target, source)) return@withIOContext target
        if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
            return@withIOContext null
        }

        val mutex = pathLocks.getOrPut(source.cacheIdentity) { Mutex() }
        mutex.withLock {
            if (shouldSkipFailed(failure)) return@withLock null
            if (isFresh(target, source)) return@withLock target
            if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
                return@withLock null
            }
            extractSemaphore.withPermit {
                if (isFresh(target, source)) return@withPermit target
                val frame = try {
                    withTimeout(EXTRACT_TIMEOUT_MS) {
                        extractThumbnailFrame(source, directory, cacheKey)
                    }
                } catch (e: TimeoutCancellationException) {
                    return@withPermit null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    return@withPermit null
                }
                if (frame == null) {
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
                    OriginDiskCache.scheduleTrim()
                } finally {
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                }
                target
            }
        }
    }

    private fun shouldSkipFailed(failure: File): Boolean {
        if (!failure.isFile) return false
        val isCurrent = runCatching { failure.readText() == FAILURE_MARKER_DECODE }.getOrDefault(false)
        if (
            isCurrent &&
            System.currentTimeMillis() - failure.lastModified() < FAILURE_RETRY_MS
        ) {
            return true
        }
        failure.delete()
        return false
    }

    private fun isFresh(target: File, source: VideoThumbnailSource): Boolean {
        if (!target.isFile || target.length() <= 0L) return false
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
            extractFrame(PfdArchiveByteSource(pfd), allowSeekScan = true)
        }
        is VideoThumbnailSource.Smb -> {
            val smb = SmbRepository.load(source.sourceId) ?: error("SMB source missing")
            val password = SmbPasswordStore.get(source.sourceId)
            extractNetworkSparseFrame(directory, cacheKey, source.remoteRelativeFile) { temporary, tail ->
                SmbCache.withBrowseThumbFetchSlot {
                    if (tail) {
                        SmbGateway.downloadFileTail(
                            source = smb,
                            password = password,
                            relativeFilePath = source.remoteRelativeFile,
                            destination = temporary,
                            maxBytes = MAX_NETWORK_TAIL_BYTES,
                        )
                    } else {
                        SmbGateway.downloadFilePrefix(
                            source = smb,
                            password = password,
                            relativeFilePath = source.remoteRelativeFile,
                            destination = temporary,
                            maxBytes = MAX_NETWORK_PREFIX_BYTES,
                        )
                    }
                }
            }
        }
        is VideoThumbnailSource.WebDav -> {
            val webDav = WebDavRepository.load(source.sourceId) ?: error("WebDAV source missing")
            val password = WebDavPasswordStore.get(source.sourceId)
            extractNetworkSparseFrame(directory, cacheKey, source.remoteRelativeFile) { temporary, tail ->
                WebDavCache.withBrowseThumbFetchSlot {
                    if (tail) {
                        WebDavClient.downloadFileTail(
                            source = webDav,
                            password = password,
                            relativeFilePath = source.remoteRelativeFile,
                            destination = temporary,
                            maxBytes = MAX_NETWORK_TAIL_BYTES,
                        )
                    } else {
                        WebDavClient.downloadFilePrefix(
                            source = webDav,
                            password = password,
                            relativeFilePath = source.remoteRelativeFile,
                            destination = temporary,
                            maxBytes = MAX_NETWORK_PREFIX_BYTES,
                        )
                    }
                }
            }
        }
    }

    /**
     * Gallery-shaped network path: one browse-slot at a time, bounded prefix, then
     * a sparse EOF tail only if the prefix has no decodable frame (moov-at-end MP4).
     */
    private suspend fun extractNetworkSparseFrame(
        directory: File,
        cacheKey: String,
        remoteFileName: String,
        download: suspend (temporary: File, tail: Boolean) -> Long,
    ): Bitmap? {
        val extension = remoteFileName
            .substringAfterLast('.', "video")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifEmpty { "video" }
        val temporary = File(directory, "$cacheKey.tmp.${System.nanoTime()}.$extension")
        return try {
            val downloaded = download(temporary, false)
            if (downloaded <= 0L || temporary.length() <= 0L) {
                null
            } else {
                extractSparseFileFrame(temporary) ?: run {
                    val tailDownloaded = download(temporary, true)
                    if (tailDownloaded > 0L) extractSparseFileFrame(temporary) else null
                }
            }
        } finally {
            temporary.delete()
        }
    }

    /**
     * Sparse prefix+tail files have holes in the middle. Do not ask MMR for a
     * "representative" or 1/3–2/3 frame — those seeks land in the hole.
     */
    private fun extractSparseFileFrame(file: File): Bitmap? = decodeThumbnailFrame(allowSeekScan = false) {
        it.setDataSource(file.absolutePath)
    }

    private fun extractFrame(source: ArchiveByteSource, allowSeekScan: Boolean): Bitmap? {
        val dataSource = ArchiveMediaDataSource(source)
        return try {
            decodeThumbnailFrame(allowSeekScan) { it.setDataSource(dataSource) }
        } finally {
            dataSource.close()
        }
    }

    private inline fun decodeThumbnailFrame(
        allowSeekScan: Boolean,
        setDataSource: (MediaMetadataRetriever) -> Unit,
    ): Bitmap? = decodeFrame(setDataSource) {
        if (allowSeekScan) selectThumbnailFrame() else selectStartFrame()
    }

    /**
     * Artwork or an early sync frame still inside the downloaded prefix.
     * TV rips often open on a black fade — t=0 / cover art is not enough.
     */
    private fun MediaMetadataRetriever.selectStartFrame(): Bitmap? {
        var best: Bitmap? = null
        var bestScore = -1
        fun consider(candidate: Bitmap?) {
            if (candidate == null) return
            val score = visibleSampleCount(candidate)
            if (score > bestScore) {
                best?.recycle()
                best = candidate
                bestScore = score
            } else {
                candidate.recycle()
            }
        }
        consider(
            runCatching {
                embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.getOrNull(),
        )
        if (bestScore >= MIN_VISIBLE_SAMPLES) return best
        for (timeUs in PREFIX_SEEK_TIMES_US) {
            consider(
                runCatching {
                    getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull(),
            )
            if (bestScore >= MIN_VISIBLE_SAMPLES) return best
        }
        if (bestScore < MIN_ACCEPT_SAMPLES) {
            best?.recycle()
            return null
        }
        return best
    }

    private fun cacheKey(source: VideoThumbnailSource): String = sha256("$CACHE_FORMAT_VERSION:${source.cacheIdentity}")

    /**
     * Local files only. Representative frame first; extra seeks only if it is mostly black.
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

/**
 * Local PFD path only. [bytesRead] is a hard stop so a buggy retriever cannot
 * scan an unbounded source even if a caller passes one in.
 */
private class ArchiveMediaDataSource(
    private val source: ArchiveByteSource,
    private val maxBytes: Long = 8L * 1024L * 1024L,
) : MediaDataSource() {
    private val closed = AtomicBoolean(false)
    private val bytesRead = AtomicLong(0L)

    override fun getSize(): Long = source.size

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get()) return -1
        if (bytesRead.get() >= maxBytes) return -1
        val want = minOf(size.toLong(), maxBytes - bytesRead.get()).toInt()
        val read = source.readAt(position, buffer, offset, want)
        if (read > 0) bytesRead.addAndGet(read.toLong())
        return if (read <= 0) -1 else read
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { source.close() }
    }
}
