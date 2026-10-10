package com.hippo.ehviewer.library.videothumb

import android.graphics.Bitmap
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Limits concurrent transfers to one host; held only while bytes are being fetched. */
interface FetchGate {
    suspend fun <T> withSlot(block: suspend () -> T): T

    object None : FetchGate {
        override suspend fun <T> withSlot(block: suspend () -> T): T = block()
    }
}

/**
 * Index-first video thumbnails: parse the container index, fetch exactly one keyframe per
 * candidate time, decode it with [KeyframeDecoder]. Fetch runs under the caller's
 * [FetchGate]; decode runs after the gate is released, so transfers and decodes overlap.
 * All buffers are charged to [budget].
 */
object KeyframeThumbnailer {
    private const val BUDGET_BYTES = 48L * 1024 * 1024
    private const val JOB_QUOTA_BYTES = 6L * 1024 * 1024
    private const val MAX_READ_BYTES = 24 * 1024 * 1024
    private const val INDEX_TIMEOUT_MS = 6_000L
    private const val FETCH_TIMEOUT_MS = 5_000L
    private const val DECODE_TIMEOUT_MS = 3_000L
    private const val MAX_DECODES = 3

    private val budget = ByteBudget(BUDGET_BYTES)

    sealed interface Result {
        class Frame(val bitmap: Bitmap) : Result

        /** Container/codec not indexed here — caller may try another extractor. */
        data object Unsupported : Result

        /** Timeout, I/O error or budget pressure — retry on a later visit. */
        data object Transient : Result

        /** Indexed and decoded nothing usable. */
        data object Failed : Result
    }

    /**
     * Does not close [raw]. [score] rates a frame (higher = more visible content); the first
     * frame reaching [goodScore] wins, otherwise the best one seen.
     */
    suspend fun extract(
        raw: ArchiveByteSource,
        label: String,
        maxEdge: Int,
        gate: FetchGate,
        score: (Bitmap) -> Int,
        goodScore: Int,
    ): Result {
        if (raw.size <= 0L) return Result.Unsupported
        return budget.withLease(JOB_QUOTA_BYTES) { lease ->
            val source = ArchiveRangeSource(raw, lease, MAX_READ_BYTES)
            val cache = BlockCache(source)
            val index = try {
                gate.withSlot { withTimeout(INDEX_TIMEOUT_MS) { VideoContainers.open(cache) } }
            } catch (e: Throwable) {
                return@withLease failure(e, label, "index")
            }
            var best: Bitmap? = null
            var bestScore = -1
            var decodes = 0
            val seen = HashSet<Long>()
            for (timeUs in VideoContainers.candidateTimesUs(index.durationUs, raw.size)) {
                if (decodes >= MAX_DECODES || bestScore >= goodScore) break
                val mark = lease.mark()
                try {
                    val keyframe = gate.withSlot { withTimeout(FETCH_TIMEOUT_MS) { index.keyframeNear(timeUs) } } ?: continue
                    if (!seen.add(keyframe.id)) continue
                    decodes++
                    val bitmap = KeyframeDecoder.decode(keyframe, maxEdge, DECODE_TIMEOUT_MS) ?: continue
                    val s = score(bitmap)
                    if (s > bestScore) {
                        best?.recycle()
                        best = bitmap
                        bestScore = s
                    } else {
                        bitmap.recycle()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException && e !is TimeoutCancellationException) {
                        best?.recycle()
                        throw e
                    }
                    val result = failure(e, label, "keyframe@${timeUs / 1000}ms")
                    if (best == null) return@withLease result
                    break
                } finally {
                    lease.resetTo(mark)
                }
            }
            best?.let { Result.Frame(it) } ?: Result.Failed
        }
    }

    private fun failure(e: Throwable, label: String, stage: String): Result {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        val result = when (e) {
            is TimeoutCancellationException, is IOException -> Result.Transient
            is VideoThumbBudgetException -> if (e.transient) Result.Transient else Result.Failed
            else -> Result.Unsupported
        }
        logcat("VideoThumb") { "keyframe $stage ($label): $result ${e.javaClass.simpleName} ${e.message}" }
        return result
    }
}
