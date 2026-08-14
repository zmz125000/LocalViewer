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
 * **drop the SMB/WebDAV handle**, then decode from RAM. Holes are zeros (same as a
 * sparse file). Returning −1 mid-file, or [MediaMetadataRetriever.release] from
 * the timeout thread, leaves `media.extractor` at 100% CPU. Timeout only abandons
 * the wait.
 *
 * Disk: `cache/video_thumb_cache/` — same parent budget as other browse thumbs
 * ([OriginDiskCache.THUMB_BUDGET_BYTES]). A JPEG there is used with **no expiry**
 * and without a network round-trip.
 */
object VideoThumbnail {
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000
    private const val MAX_CONCURRENT_EXTRACTIONS = 2

    /** Fetch head/tail then close the remote handle. */
    private const val PROBE_IO_TIMEOUT_MS = 2_500L

    /** Native setDataSource + first keyframe. Timeout abandons; does not abort extractor. */
    private const val DECODE_TIMEOUT_MS = 1_500L

    private const val PROBE_HEAD_BYTES = 2 * 1024 * 1024

    /** Phone MP4s keep `moov` at EOF; this sample's moov is ~600 KiB. */
    private const val PROBE_TAIL_BYTES = 2 * 1024 * 1024
    private const val SAMPLE_GRID = 12
    private const val BLACK_LUMA = 24
    private const val MIN_VISIBLE_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 15 / 100
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
        val target = File(cacheDirectory(), "${cacheKey(source)}.jpg")
        return target.takeIf(::isCachedJpeg)
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
        // Disk hit first: no expiry, no network, ignore a later .failed marker.
        if (isCachedJpeg(target)) return@withIOContext target
        if (shouldSkipFailed(failure)) return@withIOContext null
        if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
            return@withIOContext null
        }

        val mutex = pathLocks.getOrPut(source.cacheIdentity) { Mutex() }
        mutex.withLock {
            if (isCachedJpeg(target)) return@withLock target
            if (shouldSkipFailed(failure)) return@withLock null
            if (source.isNetwork && !Settings.downloadNetworkVideoThumbs.value) {
                return@withLock null
            }
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

    private fun isCachedJpeg(target: File): Boolean = target.isFile && target.length() > 0L

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
                    val tail = if (size > head.size) {
                        val tailLen = minOf(PROBE_TAIL_BYTES.toLong(), size - head.size).toInt()
                        readPrefix(raw, size - tailLen, tailLen)
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

    /**
     * Decode from the in-memory head/tail. [ProbeMediaDataSource] reports the real
     * file size so `moov` at EOF stays at its offset; holes are zeros, never −1.
     */
    private suspend fun decodeSnapshot(snapshot: ProbeSnapshot, label: String): Bitmap? {
        val data = ProbeMediaDataSource(snapshot.fileSize, snapshot.head, snapshot.tail)
        return decodeFrameWatchdog(
            label = label,
            timeoutMs = DECODE_TIMEOUT_MS,
            setDataSource = { it.setDataSource(data) },
            getFrame = {
                runCatching {
                    getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
            },
        )
    }

    /** Local files go through the platform path — no 8 MiB MediaDataSource cap. */
    private suspend fun extractLocalFrame(source: VideoThumbnailSource.Local): Bitmap? {
        val file = File(source.path)
        if (source.path.startsWith('/') && file.isFile) {
            return decodeFrameWatchdog(
                label = file.name,
                timeoutMs = DECODE_TIMEOUT_MS,
                setDataSource = { it.setDataSource(file.absolutePath) },
                getFrame = { selectThumbnailFrame() },
            )
        }
        val pfd = source.path.toPath().openFileDescriptor("r")
        val length = pfd.statSize.coerceAtLeast(0L)
        return decodeFrameWatchdog(
            label = source.path,
            timeoutMs = DECODE_TIMEOUT_MS,
            setDataSource = { it.setDataSource(pfd.fileDescriptor, 0L, length) },
            getFrame = { selectThumbnailFrame() },
            cleanup = { runCatching { pfd.close() } },
        )
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
     * MMR [getFrameAtTime] / [setDataSource] are blocking native calls into
     * `media.extractor`. Coroutine [withTimeout] cannot abort them.
     *
     * Decode on a daemon thread so the permit is released on timeout. **Do not**
     * [MediaMetadataRetriever.release] or close the data source from this thread —
     * that leaves a `mediaex` binder thread at 100% CPU after the app is gone.
     * The worker [release]s after native returns.
     */
    private suspend fun decodeFrameWatchdog(
        label: String,
        timeoutMs: Long,
        setDataSource: (MediaMetadataRetriever) -> Unit,
        getFrame: MediaMetadataRetriever.() -> Bitmap?,
        cleanup: () -> Unit = {},
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val done = CompletableDeferred<Bitmap?>()
        Thread(
            {
                try {
                    setDataSource(retriever)
                    val frame = getFrame(retriever)
                    if (!done.complete(frame)) {
                        frame?.recycle()
                    }
                } catch (e: Throwable) {
                    if (!done.complete(null)) {
                        logcat("VideoThumb") { "mmr late ($label): ${e.message}" }
                    }
                } finally {
                    runCatching { retriever.release() }
                    runCatching { cleanup() }
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
            logcat("VideoThumb") { "mmr timeout ${timeoutMs}ms ($label) — abandon native (no release)" }
            null
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

/**
 * Head at 0, tail at EOF, [fileSize] so `moov`-at-end stays at its real offset.
 * Mid-file holes are zeros. [readAt] returns −1 only at true EOF.
 */
private class ProbeMediaDataSource(
    private val fileSize: Long,
    private val head: ByteArray,
    private val tail: ByteArray?,
) : MediaDataSource() {
    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = readVideoThumbProbe(fileSize, head, tail, position, buffer, offset, size)

    override fun close() {}
}

/** Copy one [MediaDataSource.readAt] window from a head+tail probe. */
internal fun readVideoThumbProbe(
    fileSize: Long,
    head: ByteArray,
    tail: ByteArray?,
    position: Long,
    buffer: ByteArray,
    offset: Int,
    size: Int,
): Int {
    if (position < 0L || position >= fileSize) return -1
    if (size <= 0) return 0
    val want = minOf(size.toLong(), fileSize - position).toInt()
    val tailStart = if (tail != null) fileSize - tail.size else fileSize
    var copied = 0
    while (copied < want) {
        val pos = position + copied
        val dest = offset + copied
        val remaining = want - copied
        when {
            pos < head.size -> {
                val n = minOf(remaining, head.size - pos.toInt())
                System.arraycopy(head, pos.toInt(), buffer, dest, n)
                copied += n
            }
            pos >= tailStart && tail != null -> {
                val tailOff = (pos - tailStart).toInt()
                val n = minOf(remaining, tail.size - tailOff)
                System.arraycopy(tail, tailOff, buffer, dest, n)
                copied += n
            }
            else -> {
                val n = minOf(remaining.toLong(), tailStart - pos).toInt()
                buffer.fill(0, dest, dest + n)
                copied += n
            }
        }
    }
    return copied
}
