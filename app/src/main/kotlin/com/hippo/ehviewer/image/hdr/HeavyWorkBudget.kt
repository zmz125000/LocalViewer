package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MIB = 1024L * 1024L

/**
 * Byte gate for in-flight camera RAW / JXR work.
 *
 * A charge larger than [limit] is clamped to [limit], so that one frame still
 * runs and the next waits. Smaller charges share the budget.
 */
internal class ByteBudget(val limit: Long) {
    private val mutex = Mutex()
    private var used = 0L

    suspend fun <T> use(bytes: Long, block: suspend () -> T): T {
        val cap = limit.coerceAtLeast(1L)
        val charge = bytes.coerceAtLeast(1L).coerceAtMost(cap)
        while (true) {
            val granted = mutex.withLock {
                if (used == 0L || used + charge <= limit) {
                    used += charge
                    true
                } else {
                    false
                }
            }
            if (granted) {
                break
            }
            delay(BUDGET_POLL_MS)
        }
        try {
            return block()
        } finally {
            mutex.withLock { used = (used - charge).coerceAtLeast(0L) }
        }
    }
}

internal fun heavyBudgetLimit(heapBytes: Long, percent: Long, capBytes: Long, minBytes: Long): Long {
    val heap = heapBytes.coerceAtLeast(64L * MIB)
    val target = heap * percent / 100
    val cap = minOf(capBytes, heap / 2).coerceAtLeast(minBytes)
    return target.coerceIn(minBytes, cap)
}

/** In-flight decoded bitmap peak (packed buffer + bitmap). */
internal fun decodedBitmapBudgetLimit(heapBytes: Long, cacheOff: Boolean): Long = if (cacheOff) {
    heavyBudgetLimit(heapBytes, percent = 25, capBytes = 96L * MIB, minBytes = 48L * MIB)
} else {
    heavyBudgetLimit(heapBytes, percent = 35, capBytes = 160L * MIB, minBytes = 64L * MIB)
}

/** Compressed JXR / RAW bytes held by prefetch. */
internal fun prefetchBudgetLimit(heapBytes: Long, cacheOff: Boolean): Long = if (cacheOff) {
    heavyBudgetLimit(heapBytes, percent = 20, capBytes = 64L * MIB, minBytes = 32L * MIB)
} else {
    heavyBudgetLimit(heapBytes, percent = 25, capBytes = 96L * MIB, minBytes = 32L * MIB)
}

/**
 * Peak bytes for one heavy decode.
 *
 * [maxEdge] 0 stands for an unknown full-res frame and uses 4096, not 8192,
 * so a reader-sized frame can share the budget. An 8192 RAW still fills it.
 * 8 bytes/pixel is RGBA_F16. Two copies are the packed buffer and the bitmap.
 */
internal fun decodedBitmapCharge(maxEdge: Int): Long {
    val edge = when {
        maxEdge <= 0 -> 4096
        else -> maxEdge.coerceIn(1, 8192)
    }.toLong()
    val pixels = edge * edge * 2 / 3
    return pixels * 8 * 2
}

internal fun isHeavyPrefetchExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return isRawStillExtension(e) || e == "jxr" || e == "wdp" || e == "hdp"
}

/** Estimate used when the listing has no file length. */
internal fun heavyPrefetchCharge(ext: String?): Long {
    val e = ext?.lowercase()?.removePrefix(".")
    return if (isRawStillExtension(e)) 24L * MIB else 8L * MIB
}

@PublishedApi
internal object HeavyWork {
    val bitmapBudget: ByteBudget by lazy {
        ByteBudget(decodedBitmapBudgetLimit(OSUtils.appMaxMemory, Settings.disableReaderNetworkCache.value))
    }
    val prefetchBudget: ByteBudget by lazy {
        ByteBudget(prefetchBudgetLimit(OSUtils.appMaxMemory, Settings.disableReaderNetworkCache.value))
    }

    suspend fun <T> withDecodedBitmap(maxEdge: Int, block: suspend () -> T): T = bitmapBudget.use(decodedBitmapCharge(maxEdge), block)

    /** SMB / WebDAV page loaders are public inline, so this entry point is published. */
    @PublishedApi
    internal suspend fun <T> withPrefetch(fileName: String, block: suspend () -> T): T {
        val ext = FileUtils.getExtensionFromFilename(fileName)
        if (!isHeavyPrefetchExtension(ext)) {
            return block()
        }
        return prefetchBudget.use(heavyPrefetchCharge(ext), block)
    }
}

private const val BUDGET_POLL_MS = 16L
