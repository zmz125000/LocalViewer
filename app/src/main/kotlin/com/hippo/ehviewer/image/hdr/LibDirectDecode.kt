package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import com.ehviewer.core.files.read
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.decodeAvifBytesToDirect
import com.hippo.ehviewer.jni.decodeJxlBytesToDirect
import com.hippo.ehviewer.jni.decodeJxrBytesToDirect
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

/**
 * Result of lib still → direct [Bitmap] (no Ultra HDR JPEG convert).
 */
data class LibDirectResult(
    val bitmap: Bitmap,
    /** Absolute HDR content (PQ/HLG / high peak) — drives window COLOR_MODE_HDR. */
    val isHdrContent: Boolean,
    /** Linear content boost for [android.view.Window.setDesiredHdrHeadroom]. */
    val contentHdrBoost: Float,
)

/**
 * Decode JXR / JXL / PQ-AVIF straight to a display [Bitmap] for the experimental
 * reader present mode ([com.hippo.ehviewer.Settings.readerLibDirectBitmap]).
 */
object LibDirectDecode {
    /**
     * @param maxEdge 0 = full res; else long-edge cap (reader decode size).
     * @return null if not a lib route, decode failed, or unsupported ABI.
     */
    suspend fun decode(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int = 0,
    ): LibDirectResult? = withContext(Dispatchers.IO) {
        val bytes = readBytes(src) ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val route = classify(bytes, bytes.size, fileNameHint)
        if (route !is StillRoute.Lib) return@withContext null
        val outInfo = IntArray(4)
        val outBoost = FloatArray(1)
        val pixels = when (route.codec) {
            LibCodec.Jxl -> decodeJxlBytesToDirect(bytes, maxEdge, outInfo, outBoost)
            LibCodec.Jxr -> decodeJxrBytesToDirect(bytes, maxEdge, outInfo, outBoost)
            LibCodec.AvifPq -> decodeAvifBytesToDirect(bytes, maxEdge, outInfo, outBoost)
        } ?: return@withContext null
        val w = outInfo[0]
        val h = outInfo[1]
        val format = outInfo[2]
        val isHdr = outInfo[3] != 0
        if (w <= 0 || h <= 0) return@withContext null
        val bitmap = pixelsToBitmap(pixels, w, h, format == 1, isHdr) ?: return@withContext null
        val boost = outBoost[0].coerceIn(1f, 64f)
        LibDirectResult(
            bitmap = bitmap,
            isHdrContent = isHdr,
            contentHdrBoost = if (isHdr) boost else 1f,
        )
    }

    private fun readBytes(src: ImageSource): ByteArray? = when (src) {
        is PathSource -> runCatching { src.source.read { readByteArray() } }.getOrNull()
        is ByteBufferSource -> {
            val dup = src.source.asReadOnlyBuffer()
            val n = dup.remaining()
            if (n <= 0) null else ByteArray(n).also { dup.get(it) }
        }
    }

    /**
     * @param f16 true → [Bitmap.Config.RGBA_F16] linear; false → ARGB_8888 sRGB
     */
    private fun pixelsToBitmap(pixels: ByteArray, w: Int, h: Int, f16: Boolean, isHdr: Boolean): Bitmap? {
        return runCatching {
            val config = if (f16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.Config.RGBA_F16
            } else {
                Bitmap.Config.ARGB_8888
            }
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && f16) {
                // Linear extended sRGB: values > 1.0 carry headroom for window HDR.
                val cs = ColorSpace.get(
                    if (isHdr) {
                        ColorSpace.Named.LINEAR_EXTENDED_SRGB
                    } else {
                        ColorSpace.Named.SRGB
                    },
                )
                Bitmap.createBitmap(w, h, config, true, cs)
            } else {
                Bitmap.createBitmap(w, h, config)
            }
            val expected = if (config == Bitmap.Config.RGBA_F16) w * h * 8 else w * h * 4
            if (pixels.size < expected) {
                bitmap.recycle()
                return@runCatching null
            }
            val buf = ByteBuffer.wrap(pixels, 0, expected)
            bitmap.copyPixelsFromBuffer(buf)
            bitmap.prepareToDraw()
            bitmap
        }.getOrNull()
    }
}
