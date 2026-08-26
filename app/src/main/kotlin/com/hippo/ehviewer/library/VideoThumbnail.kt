package com.hippo.ehviewer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    val fileName: String

    data class Local(val path: String) : VideoThumbnailSource {
        override val isNetwork: Boolean = false
        override val fileName: String
            get() = path.substringAfterLast('/').substringAfterLast('\\')
        override val cacheIdentity: String
            get() {
                val file = File(path)
                // v6: 2s→30s keyframe seek + ranged RAM network decode.
                return "v6:local:$path:${file.length()}:${file.lastModified()}"
            }
    }

    data class Smb(val sourceId: Long, val remoteRelativeFile: String) : VideoThumbnailSource {
        override val isNetwork: Boolean = true
        override val fileName: String
            get() = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\')
        override val cacheIdentity = "v6:smb:$sourceId:$remoteRelativeFile"
    }

    data class WebDav(val sourceId: Long, val remoteRelativeFile: String) : VideoThumbnailSource {
        override val isNetwork: Boolean = true
        override val fileName: String
            get() = remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\')
        override val cacheIdentity = "v6:webdav:$sourceId:$remoteRelativeFile"
    }
}

/**
 * Lazy video frame extraction for visible browse rows.
 *
 * **Pipeline:**
 * - **Local / non-TS network:** MMR over full file or [CachingRangeMediaDataSource]
 *   (on-demand `readAt` pages in RAM) with keyframe seeks **2s → 30s** (not a
 *   contiguous 30s download).
 * - **MPEG-TS:** contiguous head probe, then early keyframe seeks only.
 * - Probe/ranged I/O and decode stay under abandon-safe timeouts; waiter never
 *   [MediaMetadataRetriever.release]s (that left `media.extractor` at 100% CPU).
 *
 * **Timeout / leave-folder safety:**
 * - [MediaMetadataRetriever] runs on [decodePool]. Waiter uses [withTimeout] only —
 *   **never** [MediaMetadataRetriever.release] from the waiter (that left
 *   `media.extractor` at 100% CPU until reboot). Worker releases after native returns.
 * - Probe I/O runs on [probePool] (not unbounded `Thread()`). Timeout **and**
 *   composition cancel both count as abandon so permits are not recycled under still-
 *   running workers (that stacked ~N stuck threads after scrolling ~30 cells).
 * - Abandoned probe/decode caps reject new work until workers finish.
 *
 * Disk: `cache/video_thumb_cache/` under [OriginDiskCache.THUMB_BUDGET_BYTES].
 */
object VideoThumbnail {
    private val EDGE_PX: Int get() = OriginDiskCache.THUMB_EDGE

    private const val FAILURE_RETRY_MS = 24L * 60 * 60 * 1_000

    /** Parallel native MMR decodes (and abandoned-worker budget). */
    private const val MAX_CONCURRENT_EXTRACTIONS = 3

    /** Parallel remote head/tail probes (pipeline with decode). */
    private const val MAX_CONCURRENT_PROBES = 3

    /** Extra abandoned native jobs allowed beyond the active decode slots. */
    private const val MAX_ABANDONED_WORKERS = 3

    /** Extra abandoned probe jobs allowed beyond active probe slots. */
    private const val MAX_ABANDONED_PROBES = 3

    /** Fetch probe then close remote. Slightly higher for 8 MiB TS heads. */
    private const val PROBE_IO_TIMEOUT_MS = 4_000L

    /** Native setDataSource + multi-seek keyframe grabs. Waiter abandons; no cross-thread release. */
    private const val DECODE_TIMEOUT_MS = 2_500L

    /**
     * Open remote + ranged MMR (prefetch + 2s/30s seeks). Longer than [DECODE_TIMEOUT_MS]
     * because I/O and decode share one waiter.
     */
    private const val RANGED_NETWORK_TIMEOUT_MS = 6_000L

    /** Leave room for release after last seek inside the decode budget. */
    private const val SEEK_BUDGET_MS = 2_000L

    private const val PROBE_HEAD_BYTES = 2 * 1024 * 1024
    private const val PROBE_TAIL_BYTES = 2 * 1024 * 1024

    /** MPEG-TS is sequential — contiguous head only (no zero-filled mid-file). */
    private const val PROBE_TS_HEAD_BYTES = 8 * 1024 * 1024

    private const val KEYFRAME_PRIMARY_US = 2_000_000L
    private const val KEYFRAME_FALLBACK_US = 30_000_000L

    private const val SAMPLE_GRID = 12
    private const val BLACK_LUMA = 24
    private const val MIN_VISIBLE_SAMPLES = SAMPLE_GRID * SAMPLE_GRID * 15 / 100

    private val extractSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)
    private val probeSemaphore = Semaphore(MAX_CONCURRENT_PROBES)
    private val pathLocks = ConcurrentHashMap<String, Mutex>()
    private val leftoverMarkersCleared = AtomicBoolean(false)
    private val abandonedWorkers = AtomicInteger(0)
    private val abandonedProbes = AtomicInteger(0)

    /**
     * Fixed pool: at most [MAX_CONCURRENT_EXTRACTIONS] + [MAX_ABANDONED_WORKERS] native
     * jobs. Full queue → reject new decode (fail thumb) instead of unbounded Thread spawn.
     */
    private val decodePool = ThreadPoolExecutor(
        MAX_CONCURRENT_EXTRACTIONS,
        MAX_CONCURRENT_EXTRACTIONS + MAX_ABANDONED_WORKERS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_CONCURRENT_EXTRACTIONS + MAX_ABANDONED_WORKERS),
        { r -> Thread(r, "video-thumb-mmr").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /**
     * Bounded probe I/O (was unbounded `Thread` per cell — timeout/cancel released the
     * probe semaphore while the old thread kept reading, stacking CPU after a grid scroll).
     */
    private val probePool = ThreadPoolExecutor(
        MAX_CONCURRENT_PROBES,
        MAX_CONCURRENT_PROBES + MAX_ABANDONED_PROBES,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_CONCURRENT_PROBES + MAX_ABANDONED_PROBES),
        { r -> Thread(r, "video-thumb-probe").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

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
            if (abandonedWorkers.get() >= MAX_ABANDONED_WORKERS) {
                logcat("VideoThumb") { "skip decode: too many abandoned extractors" }
                writeFailureMarker(failure)
                return@withLock null
            }
            if (source.isNetwork && abandonedProbes.get() >= MAX_ABANDONED_PROBES) {
                logcat("VideoThumb") { "skip probe: too many abandoned probe workers" }
                writeFailureMarker(failure)
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

    /**
     * Local: decode only. Network TS: probe head then decode. Network other: ranged
     * RAM [CachingRangeMediaDataSource] while the remote stays open (player-like seeks).
     */
    private suspend fun extractThumbnailFrame(source: VideoThumbnailSource): Bitmap? = when (source) {
        is VideoThumbnailSource.Local -> extractSemaphore.withPermit { extractLocalFrame(source) }
        is VideoThumbnailSource.Smb -> {
            val smb = SmbRepository.load(source.sourceId) ?: error("SMB source missing")
            probeSemaphore.withPermit {
                SmbCache.withBrowseThumbFetchSlot {
                    val raw = SmbArchiveByteSource(
                        source = smb,
                        password = SmbPasswordStore.get(source.sourceId),
                        remoteRelativeFile = source.remoteRelativeFile,
                        pipeline = false,
                        readahead = false,
                        yieldable = true,
                    )
                    extractNetworkFrame(raw, source)
                }
            }
        }
        is VideoThumbnailSource.WebDav -> {
            val webDav = WebDavRepository.load(source.sourceId) ?: error("WebDAV source missing")
            probeSemaphore.withPermit {
                WebDavCache.withBrowseThumbFetchSlot {
                    val raw = WebDavArchiveByteSource(
                        source = webDav,
                        password = WebDavPasswordStore.get(source.sourceId),
                        remoteRelativeFile = source.remoteRelativeFile,
                        pipeline = false,
                        readahead = false,
                    )
                    extractNetworkFrame(raw, source)
                }
            }
        }
    }

    /**
     * Sniff TS → contiguous head path; else ranged open-during-decode.
     * Always closes [raw] (success, fail, timeout, cancel).
     */
    private suspend fun extractNetworkFrame(
        raw: ArchiveByteSource,
        source: VideoThumbnailSource,
    ): Bitmap? {
        val fileName = source.fileName
        val sniffLen = minOf(188 * 8, PROBE_HEAD_BYTES, raw.size.coerceAtLeast(0L).toInt())
        val sniff = if (sniffLen > 0) readPrefix(raw, 0L, sniffLen) else ByteArray(0)
        val ts = isMpegTsVideoName(fileName) || (sniff.isNotEmpty() && looksLikeMpegTs(sniff))
        return if (ts) {
            // fetchProbeSnapshot closes [raw] on the probe worker (timeout/cancel safe).
            val snapshot = fetchProbeSnapshot(raw, source) ?: return null
            extractSemaphore.withPermit { decodeSnapshot(snapshot, source) }
        } else {
            extractSemaphore.withPermit { decodeRangedNetwork(raw, source) }
        }
    }

    /**
     * Pull probe bytes then close [raw] before native decode.
     * Runs on [probePool]; stuck SMB read is unblocked via [ArchiveByteSource.close].
     * Waiter timeout **or** cancel abandons the worker and counts it so permits are not
     * reused under still-running I/O (leave-folder / scroll-away pile-up).
     */
    private suspend fun fetchProbeSnapshot(
        raw: ArchiveByteSource,
        source: VideoThumbnailSource,
    ): ProbeSnapshot? {
        if (abandonedProbes.get() >= MAX_ABANDONED_PROBES) {
            logcat("VideoThumb") {
                "reject probe (${privacyLogLabel(source)}): abandonedProbes=${abandonedProbes.get()}"
            }
            runCatching { raw.close() }
            return null
        }
        val fileName = source.fileName
        val done = CompletableDeferred<ProbeSnapshot?>()
        val timedOut = AtomicBoolean(false)
        val task = Runnable {
            try {
                done.complete(buildContiguousTsSnapshot(raw, fileName))
            } catch (_: Throwable) {
                done.complete(null)
            } finally {
                runCatching { raw.close() }
                if (timedOut.get()) {
                    abandonedProbes.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
            }
        }
        try {
            probePool.execute(task)
        } catch (_: Throwable) {
            logcat("VideoThumb") { "probe pool full (${privacyLogLabel(source)})" }
            runCatching { raw.close() }
            return null
        }
        return try {
            withTimeout(PROBE_IO_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            abandonProbeWaiter(timedOut, done, raw, source, "timeout")
            null
        } catch (e: CancellationException) {
            // Leave folder / scroll away — same as timeout: close + count abandon.
            abandonProbeWaiter(timedOut, done, raw, source, "cancel")
            throw e
        }
    }

    private fun abandonProbeWaiter(
        timedOut: AtomicBoolean,
        done: CompletableDeferred<ProbeSnapshot?>,
        raw: ArchiveByteSource,
        source: VideoThumbnailSource,
        reason: String,
    ) {
        timedOut.set(true)
        abandonedProbes.incrementAndGet()
        if (!done.complete(null)) {
            // Worker already finished — drop abandon bookkeeping.
            timedOut.set(false)
            abandonedProbes.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        runCatching { raw.close() }
        logcat("VideoThumb") {
            "probe $reason ${PROBE_IO_TIMEOUT_MS}ms (${privacyLogLabel(source)}) — abandon " +
                "(abandonedProbes=${abandonedProbes.get()})"
        }
    }

    /** MPEG-TS / tiny file: contiguous bytes only (no sparse mid-file). */
    private fun buildContiguousTsSnapshot(raw: ArchiveByteSource, fileName: String): ProbeSnapshot? {
        val size = raw.size
        if (size <= 0L) return null
        val headCap = if (isMpegTsVideoName(fileName)) PROBE_TS_HEAD_BYTES else PROBE_HEAD_BYTES
        val headLen = minOf(headCap.toLong(), size).toInt()
        val head = readPrefix(raw, 0L, headLen)
        if (head.isEmpty()) return null
        return ProbeSnapshot(
            mode = ProbeMode.ContiguousHead,
            fileSize = head.size.toLong(),
            head = head,
            tail = null,
        )
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

    private suspend fun decodeSnapshot(
        snapshot: ProbeSnapshot,
        source: VideoThumbnailSource,
    ): Bitmap? {
        val label = privacyLogLabel(source)
        // TS contiguous head only (HeadAndTail path removed — ranged MDS replaces it).
        val tmp = File(cacheDirectory(), "mmr-${System.nanoTime()}.bin")
        return try {
            tmp.outputStream().use { it.write(snapshot.head) }
            decodeFrameWatchdog(
                label = label,
                timeoutMs = DECODE_TIMEOUT_MS,
                setDataSource = { it.setDataSource(tmp.absolutePath) },
                getFrame = { selectKeyframeFrame(preferEarlyOnly = true) },
                cleanup = { tmp.delete() },
            )
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Non-TS network: keep [raw] open, serve MMR via RAM-paged ranges (2s→30s keyframes).
     * Prefetch small head+tail so trailing-`moov` MP4 can open without a full pull.
     */
    private suspend fun decodeRangedNetwork(
        raw: ArchiveByteSource,
        source: VideoThumbnailSource,
    ): Bitmap? {
        val label = privacyLogLabel(source)
        val mds = CachingRangeMediaDataSource(raw)
        val closed = AtomicBoolean(false)
        val closeAll = {
            if (closed.compareAndSet(false, true)) {
                runCatching { mds.close() }
                runCatching { raw.close() }
            }
        }
        return try {
            val size = raw.size
            if (size > 0L) {
                val headLen = minOf(PROBE_HEAD_BYTES.toLong(), size).toInt()
                mds.prefetch(0L, headLen)
                if (size > headLen + PROBE_TAIL_BYTES) {
                    mds.prefetch(size - PROBE_TAIL_BYTES, PROBE_TAIL_BYTES)
                }
            }
            decodeFrameWatchdog(
                label = label,
                timeoutMs = RANGED_NETWORK_TIMEOUT_MS,
                setDataSource = { it.setDataSource(mds) },
                getFrame = { selectKeyframeFrame(preferEarlyOnly = false) },
                cleanup = { closeAll() },
                onAbandon = { closeAll() },
            )
        } catch (e: CancellationException) {
            closeAll()
            throw e
        } catch (e: Throwable) {
            closeAll()
            throw e
        }
    }

    /** Local files go through the platform path — full random access. */
    private suspend fun extractLocalFrame(source: VideoThumbnailSource.Local): Bitmap? {
        val label = privacyLogLabel(source)
        val file = File(source.path)
        if (source.path.startsWith('/') && file.isFile) {
            return decodeFrameWatchdog(
                label = label,
                timeoutMs = DECODE_TIMEOUT_MS,
                setDataSource = { it.setDataSource(file.absolutePath) },
                getFrame = { selectKeyframeFrame(preferEarlyOnly = false) },
            )
        }
        val pfd = source.path.toPath().openFileDescriptor("r")
        val length = pfd.statSize.coerceAtLeast(0L)
        return decodeFrameWatchdog(
            label = label,
            timeoutMs = DECODE_TIMEOUT_MS,
            setDataSource = { it.setDataSource(pfd.fileDescriptor, 0L, length) },
            getFrame = { selectKeyframeFrame(preferEarlyOnly = false) },
            cleanup = { runCatching { pfd.close() } },
        )
    }

    /**
     * Logcat identity only: extension + short hash of [VideoThumbnailSource.cacheIdentity].
     * Never full path or display name. Example: `mts#a1b2c3d4`
     */
    private fun privacyLogLabel(source: VideoThumbnailSource): String {
        val ext = source.fileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifEmpty { "bin" }
        return "$ext#${sha256(source.cacheIdentity).take(8)}"
    }

    private fun cacheKey(source: VideoThumbnailSource): String = sha256(source.cacheIdentity)

    /**
     * Keyframe seeks like a player: **2s** then **30s** (clamped to duration), then
     * cheap last resorts. Only [MediaMetadataRetriever.OPTION_CLOSEST_SYNC] —
     * [MediaMetadataRetriever.OPTION_CLOSEST] can hang MediaExtractor.
     *
     * @param preferEarlyOnly TS contiguous-head path: skip 30s when out of early range.
     */
    private fun MediaMetadataRetriever.selectKeyframeFrame(preferEarlyOnly: Boolean): Bitmap? {
        val deadline = System.nanoTime() + SEEK_BUDGET_MS * 1_000_000L
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
        fun syncAt(timeUs: Long) {
            if (System.nanoTime() >= deadline) return
            consider(
                runCatching {
                    getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull(),
            )
        }

        val durationUs = runCatching {
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.times(1_000L)
        }.getOrNull()
        val lastUs = durationUs?.minus(1_000L)?.coerceAtLeast(0L)

        fun clamp(targetUs: Long): Long {
            if (lastUs == null) return targetUs
            return minOf(targetUs, lastUs)
        }

        // Primary: ~2s keyframe.
        syncAt(clamp(KEYFRAME_PRIMARY_US))
        if (bestScore >= MIN_VISIBLE_SAMPLES) return best

        // Fallback: ~30s. TS contiguous-head path never seeks this far (out of probe).
        if (!preferEarlyOnly) {
            syncAt(clamp(KEYFRAME_FALLBACK_US))
            if (bestScore >= MIN_VISIBLE_SAMPLES) return best
        }

        // Last resorts.
        if (durationUs != null && durationUs > KEYFRAME_PRIMARY_US) {
            syncAt(clamp(durationUs / 10L))
            if (bestScore >= MIN_VISIBLE_SAMPLES) return best
        }
        if (preferEarlyOnly) {
            // Early times still inside a TS head.
            for (t in longArrayOf(1_000_000L, 3_000_000L, 5_000_000L, 0L)) {
                syncAt(clamp(t))
                if (bestScore >= MIN_VISIBLE_SAMPLES) return best
            }
        } else {
            syncAt(0L)
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
     * Decode on the bounded [decodePool] so the permit is released on timeout.
     * **Do not** [MediaMetadataRetriever.release] from the waiter — that leaves
     * `mediaex` at 100% CPU until reboot. The worker [release]s after native returns.
     */
    private suspend fun decodeFrameWatchdog(
        label: String,
        timeoutMs: Long,
        setDataSource: (MediaMetadataRetriever) -> Unit,
        getFrame: MediaMetadataRetriever.() -> Bitmap?,
        cleanup: () -> Unit = {},
        /** Called immediately on waiter timeout/cancel (e.g. close SMB under blocked readAt). */
        onAbandon: () -> Unit = {},
    ): Bitmap? {
        if (abandonedWorkers.get() >= MAX_ABANDONED_WORKERS) {
            logcat("VideoThumb") { "reject decode ($label): abandonedWorkers=${abandonedWorkers.get()}" }
            return null
        }
        val retriever = MediaMetadataRetriever()
        val done = CompletableDeferred<Bitmap?>()
        val timedOut = AtomicBoolean(false)
        val task = Runnable {
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
                if (timedOut.get()) {
                    abandonedWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
            }
        }
        try {
            decodePool.execute(task)
        } catch (_: Throwable) {
            logcat("VideoThumb") { "decode pool full ($label)" }
            runCatching { retriever.release() }
            runCatching { cleanup() }
            return null
        }
        return try {
            withTimeout(timeoutMs) { done.await() }
        } catch (e: TimeoutCancellationException) {
            abandonDecodeWaiter(timedOut, done, label, timeoutMs, "timeout", onAbandon)
            null
        } catch (e: CancellationException) {
            // Composition dispose (leave folder) must count abandon too — otherwise
            // extractSemaphore recycles under still-running MMR workers (70% sticky CPU).
            abandonDecodeWaiter(timedOut, done, label, timeoutMs, "cancel", onAbandon)
            throw e
        }
    }

    private fun abandonDecodeWaiter(
        timedOut: AtomicBoolean,
        done: CompletableDeferred<Bitmap?>,
        label: String,
        timeoutMs: Long,
        reason: String,
        onAbandon: () -> Unit,
    ) {
        timedOut.set(true)
        abandonedWorkers.incrementAndGet()
        runCatching { onAbandon() }
        // Prefer worker recycle: if still running, complete(null) loses the race harmlessly.
        if (!done.complete(null)) {
            // Already completed with a late frame — drop abandoned bookkeeping.
            timedOut.set(false)
            abandonedWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        logcat("VideoThumb") {
            "mmr $reason ${timeoutMs}ms ($label) — abandon native " +
                "(abandoned=${abandonedWorkers.get()})"
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

private enum class ProbeMode {
    /** Truncated real bytes; [ProbeSnapshot.fileSize] == head size. For MPEG-TS. */
    ContiguousHead,
}

private class ProbeSnapshot(
    val mode: ProbeMode,
    val fileSize: Long,
    val head: ByteArray,
    val tail: ByteArray?,
)

/** Copy one [MediaDataSource.readAt] window from a head+tail probe (unit tests / legacy). */
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

internal fun isMpegTsVideoName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext == "ts" || ext == "m2ts" || ext == "mts"
}

/** MPEG-TS packets start with sync byte 0x47 every 188 (or 192 Blu-ray) bytes. */
internal fun looksLikeMpegTs(head: ByteArray): Boolean {
    if (head.size < 188 * 3) return false
    fun syncAt(packet: Int, stride: Int): Boolean {
        var i = 0
        while (i < 3) {
            val off = i * stride
            if (off >= head.size || head[off] != 0x47.toByte()) return false
            i++
        }
        return true
    }
    return syncAt(0, 188) || syncAt(0, 192)
}
