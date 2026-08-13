package com.hippo.ehviewer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * Network: copy a small head (+ tail for moov-at-end) under a short I/O timeout,
 * **drop the SMB/WebDAV handle**, then decode from RAM. Native MediaExtractor can
 * hang; decode runs on a watchdog thread and is aborted after [DECODE_TIMEOUT_MS].
 *
 * Disk: `cache/video_thumb_cache/` — same parent budget as other browse thumbs
 * ([OriginDiskCache.THUMB_BUDGET_BYTES]).
 */
object VideoThumbnail {
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val REMOTE_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_CONCURRENT_EXTRACTIONS = 2

    /** Fetch head/tail then close the remote handle. */
    private const val PROBE_IO_TIMEOUT_MS = 2_500L

    /** Native setDataSource + one or two keyframe grabs. */
    private const val DECODE_TIMEOUT_MS = 1_500L

    private const val PROBE_HEAD_BYTES = 2 * 1024 * 1024
    private const val PROBE_TAIL_BYTES = 1024 * 1024
    private const val SAMPLE_GRID = 12
    private const val BLACK_LUMA = 24
    private const val MIN_VISIBLE_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 15 / 100

    /** One keyframe; a second only if the first is a black fade. */
    private val NETWORK_SEEK_TIMES_US = longArrayOf(0L, 1_000_000L)
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
            if (isFresh(target, source)) return@withLock target
            val frame = try {
                extractThumbnailFrame(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                writeFailureMarker(failure)
                return@withLock null
            }
            if (frame == null) {
                writeFailureMarker(failure)
                return@withLock null
            }
            val scaled = scale(frame)
            try {
                val temporary = File(directory, target.name + ".tmp." + System.nanoTime())
                val written = temporary.outputStream().buffered().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
                }
                if (!written || !temporary.renameTo(target)) {
                    temporary.delete()
                    return@withLock null
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
        is VideoThumbnailSource.Local -> extractSemaphore.withPermit { extractLocalFrame(source) }
        is VideoThumbnailSource.Smb -> {
            val smb = SmbRepository.load(source.sourceId) ?: error("SMB source missing")
            val snapshot = SmbCache.withBrowseThumbFetchSlot {
                val raw = SmbArchiveByteSource(
                    source = smb,
                    password = SmbPasswordStore.get(source.sourceId),
                    remoteRelativeFile = source.remoteRelativeFile,
                    pipeline = false,
                    readahead = false,
                    yieldable = true,
                )
                fetchProbeSnapshot(raw)
            } ?: return null
            extractSemaphore.withPermit { decodeSnapshot(snapshot, "smb") }
        }
        is VideoThumbnailSource.WebDav -> {
            val webDav = WebDavRepository.load(source.sourceId) ?: error("WebDAV source missing")
            val snapshot = WebDavCache.withBrowseThumbFetchSlot {
                val raw = WebDavArchiveByteSource(
                    source = webDav,
                    password = WebDavPasswordStore.get(source.sourceId),
                    remoteRelativeFile = source.remoteRelativeFile,
                    pipeline = false,
                    readahead = false,
                )
                fetchProbeSnapshot(raw)
            } ?: return null
            extractSemaphore.withPermit { decodeSnapshot(snapshot, "webdav") }
        }
    }

    /**
     * Pull a small head (+ optional tail) then close [raw] so the remote borrow
     * is gone before native decode starts. Runs on a worker so a stuck SMB read
     * cannot pin the caller; [ArchiveByteSource.close] unblocks it.
     */
    private suspend fun fetchProbeSnapshot(raw: ArchiveByteSource): ProbeSnapshot? {
        val done = CompletableDeferred<ProbeSnapshot?>()
        val thread = Thread(
            {
                try {
                    val size = raw.size
                    if (size <= 0L) {
                        done.complete(null)
                        return@Thread
                    }
                    val headLen = minOf(PROBE_HEAD_BYTES.toLong(), size).toInt()
                    val head = readPrefix(raw, 0L, headLen)
                    if (head.isEmpty()) {
                        done.complete(null)
                        return@Thread
                    }
                    val tail = if (size > head.size + PROBE_TAIL_BYTES) {
                        readPrefix(raw, size - PROBE_TAIL_BYTES, PROBE_TAIL_BYTES)
                    } else {
                        null
                    }
                    done.complete(ProbeSnapshot(size, head, tail))
                } catch (_: Throwable) {
                    done.complete(null)
                } finally {
                    runCatching { raw.close() }
                }
            },
            "video-thumb-probe",
        ).apply {
            isDaemon = true
            start()
        }
        return try {
            withTimeout(PROBE_IO_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            logcat("VideoThumb") { "probe I/O timeout ${PROBE_IO_TIMEOUT_MS}ms" }
            runCatching { raw.close() }
            thread.interrupt()
            null
        } catch (e: CancellationException) {
            runCatching { raw.close() }
            thread.interrupt()
            throw e
        }
    }

    private fun readPrefix(source: ArchiveByteSource, offset: Long, max: Int): ByteArray {
        val buf = ByteArray(max)
        var filled = 0
        while (filled < max) {
            val n = source.readAt(offset + filled, buf, filled, max - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled == max) buf else buf.copyOf(filled)
    }

    private suspend fun decodeSnapshot(snapshot: ProbeSnapshot, label: String): Bitmap? {
        val dataSource = SnapshotMediaDataSource(snapshot)
        return try {
            decodeFrameWatchdog(
                label = label,
                timeoutMs = DECODE_TIMEOUT_MS,
                abort = { runCatching { dataSource.close() } },
                setDataSource = { it.setDataSource(dataSource) },
                getFrame = { selectStartFrame(NETWORK_SEEK_TIMES_US) },
            )
        } finally {
            dataSource.close()
        }
    }

    /** Local files go through the platform path — no 8 MiB MediaDataSource cap. */
    private suspend fun extractLocalFrame(source: VideoThumbnailSource.Local): Bitmap? {
        val file = File(source.path)
        if (source.path.startsWith('/') && file.isFile) {
            return decodeFrameWatchdog(
                label = file.name,
                timeoutMs = DECODE_TIMEOUT_MS,
                abort = {},
                setDataSource = { it.setDataSource(file.absolutePath) },
                getFrame = { selectThumbnailFrame() },
            )
        }
        val pfd = source.path.toPath().openFileDescriptor("r")
        return try {
            val length = pfd.statSize.coerceAtLeast(0L)
            decodeFrameWatchdog(
                label = source.path,
                timeoutMs = DECODE_TIMEOUT_MS,
                abort = { runCatching { pfd.close() } },
                setDataSource = { it.setDataSource(pfd.fileDescriptor, 0L, length) },
                getFrame = { selectThumbnailFrame() },
            )
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
                    // CLOSEST (non-sync) can hang MediaExtractor on HEVC/AV1 / broken GOPs.
                    getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull(),
            )
            if (bestScore >= MIN_VISIBLE_SAMPLES) return best
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

    /**
     * MMR [getFrameAtTime] / [setDataSource] are blocking native calls
     * (MediaExtractor). Coroutine [withTimeout] never fires while they run, so two
     * hung files used to occupy [extractSemaphore] and the SMB thumb slot forever.
     *
     * Decode on a daemon thread. On timeout: close the data source (unblocks SMB
     * readAt) and [MediaMetadataRetriever.release] to try to stop the extractor.
     * The native thread may linger; the permit is released so other thumbs proceed.
     */
    private suspend fun decodeFrameWatchdog(
        label: String,
        timeoutMs: Long,
        abort: () -> Unit,
        setDataSource: (MediaMetadataRetriever) -> Unit,
        getFrame: MediaMetadataRetriever.() -> Bitmap?,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val done = CompletableDeferred<Bitmap?>()
        val thread = Thread(
            {
                try {
                    setDataSource(retriever)
                    val frame = getFrame(retriever)
                    if (!done.complete(frame)) {
                        frame?.recycle()
                    }
                } catch (e: Throwable) {
                    if (!done.complete(null)) {
                        logcat("VideoThumb") { "mmr abort after timeout ($label): ${e.message}" }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            },
            "video-thumb-mmr",
        ).apply {
            isDaemon = true
            start()
        }
        return try {
            withTimeout(timeoutMs) { done.await() }
        } catch (e: TimeoutCancellationException) {
            logcat("VideoThumb") { "mmr timeout ${timeoutMs}ms ($label) — abort extractor" }
            runCatching { abort() }
            runCatching { retriever.release() }
            thread.interrupt()
            null
        } catch (e: CancellationException) {
            runCatching { abort() }
            runCatching { retriever.release() }
            thread.interrupt()
            throw e
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

private class ProbeSnapshot(
    val fileSize: Long,
    val head: ByteArray,
    val tail: ByteArray?,
)

/** Serves only the prefetched head / tail. Mid-file reads are EOF. */
private class SnapshotMediaDataSource(
    private val snapshot: ProbeSnapshot,
) : MediaDataSource() {
    private val closed = AtomicBoolean(false)

    override fun getSize(): Long = snapshot.fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get() || size <= 0 || Thread.currentThread().isInterrupted) return -1
        val fileSize = snapshot.fileSize
        if (position < 0L || position >= fileSize) return 0
        val fromHead = copyFrom(snapshot.head, base = 0L, position, buffer, offset, size)
        if (fromHead > 0) return fromHead
        val tail = snapshot.tail ?: return -1
        val tailStart = fileSize - tail.size
        if (position < tailStart) return -1
        return copyFrom(tail, tailStart, position, buffer, offset, size)
    }

    private fun copyFrom(
        chunk: ByteArray,
        base: Long,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        val rel = (position - base).toInt()
        if (rel < 0 || rel >= chunk.size) return 0
        val n = minOf(size, chunk.size - rel)
        if (n <= 0) return 0
        System.arraycopy(chunk, rel, buffer, offset, n)
        return n
    }

    override fun close() {
        closed.set(true)
    }
}
