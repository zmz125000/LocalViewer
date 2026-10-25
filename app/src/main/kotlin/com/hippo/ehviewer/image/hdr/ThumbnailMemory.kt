package com.hippo.ehviewer.image.hdr

import android.app.ActivityManager
import android.graphics.BitmapFactory
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.jni.probeStillExportSize
import java.io.File
import kotlin.math.min
import okio.Path
import splitties.init.appCtx

/** Thumbnail scratch space is independent of reader/export capacity and job-count limits. */
internal object ThumbnailMemory {
    private const val MIB = 1024L * 1024
    private val heapCapacity = (Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(1)
    private val heap = ExportMemoryBudget(heapCapacity)
    private val native by lazy {
        val info = ActivityManager.MemoryInfo()
        runCatching { appCtx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) }
        val capacity = minOf(
            (info.totalMem / 16).takeIf { it > 0 } ?: heapCapacity,
            (info.availMem / 8).takeIf { it > 0 } ?: heapCapacity,
            256 * MIB,
        ).coerceAtLeast(1)
        ExportMemoryBudget(capacity)
    }

    private fun kind(codec: LibCodec?) = when (codec) {
        LibCodec.Jxl -> 0
        LibCodec.Jxr -> 1
        else -> -1
    }

    suspend fun <T> withPath(source: Path, codec: LibCodec?, edge: Int, work: suspend () -> T): T {
        // Header-only probes happen before reading the full compressed source into RAM.
        var sourceBytes = runCatching { source.metadataOrNull()?.size }.getOrNull() ?: -1L
        val size = runCatching {
            source.openFileDescriptor("r")?.use { descriptor ->
                if (sourceBytes < 0) sourceBytes = descriptor.statSize
                if (kind(codec) >= 0) {
                    probeStillExportSize("/proc/self/fd/${descriptor.fd}", kind(codec))
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                    pack(bounds.outWidth, bounds.outHeight)
                }
            } ?: 0L
        }.getOrDefault(0L)
        return withEstimate(sourceBytes, size, codec, edge, mapped = true, work = work)
    }

    suspend fun <T> withBytes(bytes: ByteArray, codec: LibCodec?, edge: Int, work: suspend () -> T): T {
        val size = runCatching {
            if (kind(codec) >= 0) {
                // Reuse the bounded native header probes without copying a whole download.
                // An IFD/basic-info beyond this prefix is unknown and runs alone.
                val header = File.createTempFile("thumb-bounds", ".bin", appCtx.cacheDir)
                try {
                    header.outputStream().use { it.write(bytes, 0, min(bytes.size, 256 * 1024)) }
                    probeStillExportSize(header.absolutePath, kind(codec))
                } finally {
                    header.delete()
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                pack(bounds.outWidth, bounds.outHeight)
            }
        }.getOrDefault(0L)
        return withEstimate(bytes.size.toLong(), size, codec, edge, mapped = false, work = work)
    }

    private fun pack(width: Int, height: Int): Long = if (width > 0 && height > 0) (width.toLong() shl 32) or height.toLong() else 0L

    private suspend fun <T> withEstimate(sourceBytes: Long, size: Long, codec: LibCodec?, edge: Int, mapped: Boolean, work: suspend () -> T): T {
        val w = size ushr 32
        val h = size and 0xffffffffL
        val pixels = if (w in 1..65536 && h in 1..65536) w * h else 0L
        val thumb = edge.coerceIn(64, 2048).toLong().let { it * it * 24 }
        val compressed = if (sourceBytes >= 0) sourceBytes.coerceAtMost(Long.MAX_VALUE / 4) * 2 else Long.MAX_VALUE / 4
        // JNI can pin/copy the compressed array; JXL still needs full-frame float
        // planes even when progressive thumbnail output is requested. Account for
        // full source planes conservatively for all native thumbnail decoders.
        // Bitmap pixel planes live on the native heap on supported Android versions.
        val heapBytes = (if (mapped) 0 else compressed) + MIB
        val nativeBytes = if (codec != LibCodec.Raw) {
            if (pixels == 0L) Long.MAX_VALUE else pixels * (if (codec == null) 8 else 64) + thumb + compressed
        } else {
            thumb + compressed
        }
        return heap.withBytes(heapBytes) { native.withBytes(nativeBytes, work) }
    }
}
