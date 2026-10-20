package com.hippo.ehviewer.image.hdr

import android.app.ActivityManager
import com.hippo.ehviewer.jni.probeStillExportSize
import com.hippo.ehviewer.util.OSUtils
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** Accounts for decoder, JNI handoff and encoder buffers throughout one export. */
internal class ExportMemoryBudget(private val capacity: Long) {
    private val mutex = Mutex()
    private val changed = MutableStateFlow(0L)
    private var used = 0L

    init {
        require(capacity > 0)
    }

    suspend fun <T> withBytes(estimate: Long, work: suspend () -> T): T {
        // A frame larger than the budget runs alone; it is never silently resized.
        val weight = estimate.coerceIn(1, capacity)
        while (true) {
            val version = changed.value
            val acquired = mutex.withLock {
                if (weight <= capacity - used) {
                    used += weight
                    true
                } else {
                    false
                }
            }
            if (acquired) break
            changed.first { it != version }
        }
        try {
            return work()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    used -= weight
                    changed.value++
                }
            }
        }
    }
}

internal fun exportWorkingBytes(width: Long, height: Long, sourceBytes: Long, raw: Boolean): Long {
    if (width <= 0 || height <= 0 || width > 65536 || height > 65536) return Long.MAX_VALUE
    // JXL decoding includes float RGBA + half RGBA + JNI; RAW also holds sensor
    // and demosaic buffers. Include the encoder, not just the final 8-byte pixel.
    return width * height * (if (raw) 128 else 80) + sourceBytes.coerceAtLeast(0).coerceAtMost(1024L * 1024 * 1024) * 2 + 16L * 1024 * 1024
}

/** Java holds the packed F16 frame and, except for mapped RAW, encoded input. */
internal fun exportHeapBytes(width: Long, height: Long, sourceBytes: Long, raw: Boolean): Long {
    if (width <= 0 || height <= 0 || width > 65536 || height > 65536) return Long.MAX_VALUE
    return width * height * 8 + (if (raw) 0 else sourceBytes.coerceAtLeast(0)) + 8L * 1024 * 1024
}

internal fun exportNativeCapacity(totalBytes: Long, availableBytes: Long, heapBytes: Long): Long {
    val physical = if (totalBytes > 0) totalBytes / 3 else heapBytes * 2
    val available = if (availableBytes > 0) availableBytes / 4 * 3 else physical
    return minOf(physical, available).coerceAtLeast(1)
}

internal object StillExportWork {
    private val heapBudget = ExportMemoryBudget((Runtime.getRuntime().maxMemory() / 4 * 3).coerceAtLeast(1))
    private val nativeBudget by lazy {
        val info = ActivityManager.MemoryInfo()
        runCatching { appCtx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) }
        ExportMemoryBudget(exportNativeCapacity(info.totalMem.takeIf { it > 0 } ?: OSUtils.totalMemory, info.availMem, Runtime.getRuntime().maxMemory()))
    }

    suspend fun <T> withSource(file: File, leaf: String, work: suspend () -> T): T {
        val raw = isRawStillExtension(leaf.substringAfterLast('.', ""))
        val kind = if (raw) {
            2
        } else if (leaf.endsWith(".jxl", true)) {
            0
        } else {
            1
        }
        val size = runCatching { probeStillExportSize(file.absolutePath, kind) }.getOrDefault(0L)
        val width = size ushr 32
        val height = size and 0xffffffffL
        val sourceBytes = file.length()
        // Codec working buffers live on the native heap, not inside the JVM's
        // maxMemory allowance. Keep both leases through encode, with separate limits.
        return heapBudget.withBytes(exportHeapBytes(width, height, sourceBytes, raw)) {
            nativeBudget.withBytes(exportWorkingBytes(width, height, sourceBytes, raw), work)
        }
    }
}
