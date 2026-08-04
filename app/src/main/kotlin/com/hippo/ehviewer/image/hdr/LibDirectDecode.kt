package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import com.ehviewer.core.files.read
import com.hippo.ehviewer.Settings
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
    /**
     * Source was wide-gamut (Display P3 / BT.2100) before scRGB rematrix.
     * Used with advanced color for [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT] hints.
     */
    val isWideGamutSource: Boolean,
)

/**
 * Decode JXR / JXL / PQ-AVIF straight to a display [Bitmap] for the experimental
 * reader present mode ([com.hippo.ehviewer.Settings.readerLibDirectBitmap]).
 *
 * Color management: native rematrixes P3/BT.2020 linear → BT.709/scRGB so
 * [ColorSpace.Named.LINEAR_EXTENDED_SRGB] / sRGB tags are hue-correct.
 * Advanced color keeps F16 for SDR (higher bit depth) and reports wide source gamut.
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
        val forceF16 = Settings.readerAdvancedColor.value
        val outInfo = IntArray(5)
        val outBoost = FloatArray(1)
        val pixels = when (route.codec) {
            LibCodec.Jxl -> decodeJxlBytesToDirect(bytes, maxEdge, forceF16, outInfo, outBoost)
            LibCodec.Jxr -> decodeJxrBytesToDirect(bytes, maxEdge, forceF16, outInfo, outBoost)
            LibCodec.AvifPq -> decodeAvifBytesToDirect(bytes, maxEdge, forceF16, outInfo, outBoost)
        } ?: return@withContext null
        val w = outInfo[0]
        val h = outInfo[1]
        val format = outInfo[2]
        val isHdr = outInfo[3] != 0
        val gamut = outInfo[4]
        if (w <= 0 || h <= 0) return@withContext null
        val f16 = format == 1
        val bitmap = pixelsToBitmap(pixels, w, h, f16) ?: return@withContext null
        val boost = outBoost[0].coerceIn(1f, 64f)
        LibDirectResult(
            bitmap = bitmap,
            isHdrContent = isHdr,
            contentHdrBoost = if (isHdr) boost else 1f,
            isWideGamutSource = gamut == 1 || gamut == 2,
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
     * Pixels are BT.709/scRGB after native rematrix.
     * @param f16 true → [Bitmap.Config.RGBA_F16] linear; false → ARGB_8888 sRGB
     */
    private fun pixelsToBitmap(pixels: ByteArray, w: Int, h: Int, f16: Boolean): Bitmap? {
        return runCatching {
            val config = if (f16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.Config.RGBA_F16
            } else {
                Bitmap.Config.ARGB_8888
            }
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && f16) {
                // Linear extended sRGB (scRGB): values > 1.0 carry headroom for window HDR.
                // SDR advanced-color F16 uses the same CS with values typically in [0,1].
                val cs = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                Bitmap.createBitmap(w, h, config, true, cs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val cs = ColorSpace.get(ColorSpace.Named.SRGB)
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
