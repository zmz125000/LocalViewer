package com.hippo.ehviewer.image.hdr

import android.app.ActivityManager
import android.graphics.BitmapFactory
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.jni.mapStillInput
import com.hippo.ehviewer.jni.probeStillExportSize
import com.hippo.ehviewer.jni.unmapStillInput
import java.io.File
import java.nio.ByteBuffer
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
        if (!Settings.thumbnailMemoryBudget.value) return work()
        // Header-only probes happen before reading the full compressed source into RAM.
        var sourceBytes = runCatching { source.metadataOrNull()?.size }.getOrNull() ?: -1L
        var avifMemory: HeifThumbnailMemory? = null
        val size = runCatching {
            source.openFileDescriptor("r").use { descriptor ->
                if (sourceBytes < 0) sourceBytes = descriptor.statSize
                if (codec == LibCodec.AvifPq) {
                    // Do not stage a pipe just for admission; the decode stage
                    // handles it once. Unknown metadata keeps the exclusive budget.
                    val buffer = mapStillInput(descriptor.fd)
                    if (buffer != null) {
                        try {
                            avifMemory = HeifPrecisionProbe.thumbnailMemory(buffer)
                        } finally {
                            unmapStillInput(buffer)
                        }
                    }
                    avifMemory?.dimensions ?: 0L
                } else if (kind(codec) >= 0) {
                    probeStillExportSize("/proc/self/fd/${descriptor.fd}", kind(codec))
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                    pack(bounds.outWidth, bounds.outHeight)
                }
            }
        }.getOrDefault(0L)
        return withEstimate(sourceBytes, size, codec, edge, mapped = true, avifYuvBytes = avifMemory?.codedYuvBytes, work = work)
    }

    suspend fun <T> withBytes(bytes: ByteArray, codec: LibCodec?, edge: Int, work: suspend () -> T): T {
        if (!Settings.thumbnailMemoryBudget.value) return work()
        var avifMemory: HeifThumbnailMemory? = null
        val size = runCatching {
            if (codec == LibCodec.AvifPq) {
                avifMemory = HeifPrecisionProbe.thumbnailMemory(ByteBuffer.wrap(bytes))
                avifMemory?.dimensions ?: 0L
            } else if (kind(codec) >= 0) {
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
        return withEstimate(bytes.size.toLong(), size, codec, edge, mapped = false, avifYuvBytes = avifMemory?.codedYuvBytes, work = work)
    }

    /** PDF Flate/predictor buffers are allocated before the encoded thumb reaches writeThumb. */
    suspend fun <T> withPdfCover(width: Int, height: Int, streamBytes: Long, rawSamples: Boolean, edge: Int, work: suspend () -> T): T {
        if (!Settings.thumbnailMemoryBudget.value) return work()
        val pixels = if (width in 1..65536 && height in 1..65536) width.toLong() * height else 0L
        val compressed = if (streamBytes > 0) streamBytes.coerceAtMost(Long.MAX_VALUE / 4) * 2 else Long.MAX_VALUE / 4
        // Inflate, undo prediction and unpack can overlap several sample arrays.
        // JPEG streams only need the compressed payload and reduced decoder output.
        val heapBytes = if (rawSamples && pixels == 0L) Long.MAX_VALUE else compressed + (if (rawSamples) pixels * 16 else 0) + MIB
        val thumb = edge.coerceIn(64, 2048).toLong().let { it * it * 24 }
        val nativeBytes = if (pixels == 0L) Long.MAX_VALUE else pixels * 8 + thumb
        return heap.withBytes(heapBytes) { native.withBytes(nativeBytes, work) }
    }

    private fun pack(width: Int, height: Int): Long = if (width > 0 && height > 0) (width.toLong() shl 32) or height.toLong() else 0L

    private suspend fun <T> withEstimate(sourceBytes: Long, size: Long, codec: LibCodec?, edge: Int, mapped: Boolean, avifYuvBytes: Long?, work: suspend () -> T): T {
        val w = size ushr 32
        val h = size and 0xffffffffL
        val pixels = if (w in 1..65536 && h in 1..65536) w * h else 0L
        val thumb = edge.coerceIn(64, 2048).toLong().let { it * it * 24 }
        // Mapped input has one file-backed view; arrays may also be copied by JNI.
        val compressed = if (sourceBytes >= 0) sourceBytes.coerceAtMost(Long.MAX_VALUE / 4) * (if (mapped) 1 else 2) else Long.MAX_VALUE / 4
        // JNI can pin/copy the compressed array; JXL still needs full-frame float
        // planes even when progressive thumbnail output is requested. Account for
        // full source planes conservatively when a codec-specific estimate is absent.
        // Bitmap pixel planes live on the native heap on supported Android versions.
        val heapBytes = (if (mapped) 0 else compressed) + MIB
        val nativeBytes = when {
            codec == LibCodec.Raw -> thumb + compressed
            codec == LibCodec.AvifPq && avifYuvBytes != null -> {
                // dav1d reconstructs full YUV, then libavif scales before RGB/F16.
                // Reserve four YUV sets for codec references, copies and scaling,
                // including coded grid tiles, alpha and gain-map items from metadata.
                avifYuvBytes.coerceAtMost(Long.MAX_VALUE / 8) * 4 + thumb + compressed
            }
            pixels == 0L -> Long.MAX_VALUE
            codec == LibCodec.Jxr -> {
                // Match setup_full_frame's transform-domain reduction. Raw/F16
                // planes are allocated at this size, rather than at source size.
                var scale = 1L
                val longEdge = maxOf(w, h)
                while (scale < 16 && (longEdge + scale * 2 - 1) / (scale * 2) >= edge &&
                    (w + scale * 2 - 1) / (scale * 2) < (w + scale - 1) / scale &&
                    (h + scale * 2 - 1) / (scale * 2) < (h + scale - 1) / scale
                ) {
                    scale *= 2
                }
                val reducedPixels = ((w + scale - 1) / scale) * ((h + scale - 1) / scale)
                reducedPixels * 64 + thumb + compressed
            }
            else -> pixels * (if (codec == null) 8 else 64) + thumb + compressed
        }
        return heap.withBytes(heapBytes) { native.withBytes(nativeBytes, work) }
    }
}
