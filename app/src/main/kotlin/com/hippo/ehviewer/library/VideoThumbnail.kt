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
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.job
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
 * Network: [MediaMetadataRetriever] probes a keep-open SMB/WebDAV handle through
 * small [MediaDataSource.readAt]s (the old ~30 Mbps pattern). A [HeadTailBudgetSource]
 * only allows the first [PROBE_HEAD_BYTES] and last [PROBE_TAIL_BYTES], and caps
 * total transfer so a bad seek cannot pull a multi‑GB file. No sticky / FUSE / HTTP.
 *
 * Disk: `cache/video_thumb_cache/` — same parent budget as other browse thumbs
 * ([OriginDiskCache.THUMB_BUDGET_BYTES]).
 */
object VideoThumbnail {
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_CONCURRENT_EXTRACTIONS = 2
    private const val EXTRACT_TIMEOUT_MS = 12_000L

    /** MMR may read anywhere in this prefix; it typically only pulls a few hundred KiB. */
    private const val PROBE_HEAD_BYTES = 8L * 1024L * 1024L

    /** moov-at-end MP4s. */
    private const val PROBE_TAIL_BYTES = 2L * 1024L * 1024L

    /** Hard stop on cumulative probe traffic per extract. */
    private const val PROBE_MAX_TOTAL_BYTES = 8L * 1024L * 1024L
    private const val SAMPLE_GRID = 12
    private const val BLACK_LUMA = 24
    private const val MIN_VISIBLE_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 15 / 100

    /** Stops at the first non-black frame. Mid-file seeks hit the sparse hole. */
    private val SHORT_SEEK_TIMES_US = longArrayOf(0L, 1_000_000L, 2_000_000L, 3_000_000L)
    private val EXTENDED_SEEK_TIMES_US = longArrayOf(5_000_000L, 8_000_000L)
    private val NETWORK_SEEK_TIMES_US = SHORT_SEEK_TIMES_US + EXTENDED_SEEK_TIMES_US
    private val extractSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)
    private val pathLocks = ConcurrentHashMap<String, Mutex>()
    private val leftoverMarkersCleared = AtomicBoolean(false)

    fun cacheDirectory(): File = File(appCtx.applicationInfo.dataDir, "cache/video_thumb_cache").apply { mkdirs() }

    /**
     * Deletes per-file skip notes (`*.failed`) under the video-thumb data dir.
     * Does not touch JPEG thumbs. Safe if the directory is missing.
     */
    fun clearFailureMarkers() {
        val directory = File(appCtx.applicationInfo.dataDir, "cache/video_thumb_cache")
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".failed")) {
                file.delete()
            }
        }
    }

    fun cachedJpegIfPresent(source: VideoThumbnailSource): File? {
        val directory = cacheDirectory()
        val cacheKey = cacheKey(source)
        val target = File(directory, "$cacheKey.jpg")
        return if (isFresh(target, source)) target else null
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun getOrCreate(context: Context, source: VideoThumbnailSource): File? = withIOContext {
        if (!Settings.saveFileMarkers.value && leftoverMarkersCleared.compareAndSet(false, true)) {
            clearFailureMarkers()
        }
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
                        extractThumbnailFrame(source)
                    }
                } catch (e: TimeoutCancellationException) {
                    return@withPermit null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    writeFailureMarker(failure)
                    return@withPermit null
                }
                if (frame == null) {
                    writeFailureMarker(failure)
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
        if (!Settings.saveFileMarkers.value) {
            failure.delete()
            return false
        }
        if (System.currentTimeMillis() - failure.lastModified() < FAILURE_RETRY_MS) {
            return true
        }
        failure.delete()
        return false
    }

    private fun writeFailureMarker(failure: File) {
        if (!Settings.saveFileMarkers.value) return
        runCatching { failure.createNewFile() }
    }

    private fun isFresh(target: File, source: VideoThumbnailSource): Boolean {
        if (!target.isFile || target.length() <= 0L) return false
        if (!source.isNetwork) return true
        return System.currentTimeMillis() - target.lastModified() < REMOTE_CACHE_MAX_AGE_MS
    }

    private suspend fun extractThumbnailFrame(source: VideoThumbnailSource): Bitmap? = when (source) {
        is VideoThumbnailSource.Local -> extractLocalFrame(source)
        is VideoThumbnailSource.Smb -> {
            val smb = SmbRepository.load(source.sourceId) ?: error("SMB source missing")
            SmbCache.withBrowseThumbFetchSlot {
                probeRemote(
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
                probeRemote(
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

    private suspend fun probeRemote(raw: ArchiveByteSource): Bitmap? {
        val bounded = HeadTailBudgetSource(
            inner = raw,
            headBytes = PROBE_HEAD_BYTES,
            tailBytes = PROBE_TAIL_BYTES,
            maxTotalBytes = PROBE_MAX_TOTAL_BYTES,
        )
        val closeOnCancel = kotlin.coroutines.coroutineContext.job.invokeOnCompletion { bounded.close() }
        return try {
            extractProbedFrame(bounded)
        } finally {
            closeOnCancel.dispose()
            bounded.close()
        }
    }

    private fun extractProbedFrame(source: ArchiveByteSource): Bitmap? {
        val dataSource = ArchiveMediaDataSource(source, maxBytes = PROBE_MAX_TOTAL_BYTES)
        return try {
            decodeFrame({ it.setDataSource(dataSource) }) {
                selectStartFrame(NETWORK_SEEK_TIMES_US)
            }
        } finally {
            dataSource.close()
        }
    }

    /** Local files go through the platform path — no 8 MiB MediaDataSource cap. */
    private fun extractLocalFrame(source: VideoThumbnailSource.Local): Bitmap? {
        val file = File(source.path)
        if (source.path.startsWith('/') && file.isFile) {
            return decodeFrame({ it.setDataSource(file.absolutePath) }) { selectThumbnailFrame() }
        }
        val pfd = source.path.toPath().openFileDescriptor("r")
        return try {
            val length = pfd.statSize.coerceAtLeast(0L)
            decodeFrame(
                { it.setDataSource(pfd.fileDescriptor, 0L, length) },
            ) { selectThumbnailFrame() }
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * Prefer a non-black frame; if every candidate is a fade, still return the
     * best bitmap so the row is not empty.
     */
    private fun MediaMetadataRetriever.selectStartFrame(timesUs: LongArray): Bitmap? {
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
        for (timeUs in timesUs) {
            consider(
                runCatching {
                    getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull(),
            )
            if (bestScore >= MIN_VISIBLE_SAMPLES) return best
            if (timeUs > 0L) {
                consider(
                    runCatching {
                        getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    }.getOrNull(),
                )
                if (bestScore >= MIN_VISIBLE_SAMPLES) return best
            }
        }
        return best
    }

    private fun cacheKey(source: VideoThumbnailSource): String = sha256(source.cacheIdentity)

    /**
     * Local files only. Representative frame first; extra seeks only if it is mostly black.
     */
    private fun MediaMetadataRetriever.selectThumbnailFrame(): Bitmap? {
        var best: Bitmap? = null
        var bestScore = -1

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
 * Lets MMR seek, but only in the file head / tail, and only up to [maxTotalBytes]
 * of actual transfer. Mid-file reads return EOF so a representative-frame seek
 * cannot pull the middle of a 10 GB episode.
 */
private class HeadTailBudgetSource(
    private val inner: ArchiveByteSource,
    private val headBytes: Long,
    private val tailBytes: Long,
    private val maxTotalBytes: Long,
) : ArchiveByteSource {
    private val closed = AtomicBoolean(false)
    private val transferred = AtomicLong(0L)

    override val size: Long get() = inner.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (closed.get() || len <= 0) return -1
        val fileSize = size
        if (fileSize <= 0L || offset < 0L || offset >= fileSize) return 0
        val allowed = allowedLength(offset, len, fileSize)
        if (allowed <= 0) return -1
        val remain = maxTotalBytes - transferred.get()
        if (remain <= 0L) return -1
        val want = minOf(allowed.toLong(), remain).toInt()
        val read = inner.readAt(offset, buf, off, want)
        if (read > 0) transferred.addAndGet(read.toLong())
        return if (read <= 0) -1 else read
    }

    private fun allowedLength(offset: Long, len: Int, fileSize: Long): Int {
        if (offset < headBytes) {
            return minOf(len.toLong(), headBytes - offset, fileSize - offset).toInt()
        }
        val tailStart = (fileSize - tailBytes).coerceAtLeast(0L)
        if (offset >= tailStart) {
            return minOf(len.toLong(), fileSize - offset).toInt()
        }
        return 0
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { inner.close() }
    }
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
