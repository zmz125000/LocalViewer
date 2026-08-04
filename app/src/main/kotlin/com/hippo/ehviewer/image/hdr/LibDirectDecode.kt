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
     * Bitmap is wide-gamut (e.g. Display P3) or source was wide before scRGB rematrix.
     * Used with advanced color for [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT].
     */
    val isWideGamutSource: Boolean,
)

/**
 * Decode JXR / JXL / PQ-AVIF straight to a display [Bitmap] for the experimental
 * reader present mode ([com.hippo.ehviewer.Settings.readerLibDirectBitmap]).
 *
 * ## Color management
 * - **HDR:** rematrix to scRGB → [ColorSpace.Named.LINEAR_EXTENDED_SRGB] F16 + window HDR.
 * - **Advanced + SDR Display P3:** keep P3 pixels → [ColorSpace.Named.DISPLAY_P3] 8888 + WCG window.
 * - **Advanced + SDR BT.709:** F16 linear extended (high bit depth).
 * - **Default SDR:** rematrix wide → sRGB 8888.
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
        val advanced = Settings.readerAdvancedColor.value
        val outInfo = IntArray(5)
        val outBoost = FloatArray(1)
        val pixels = when (route.codec) {
            LibCodec.Jxl -> decodeJxlBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
            LibCodec.Jxr -> decodeJxrBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
            LibCodec.AvifPq -> decodeAvifBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
        } ?: return@withContext null
        val w = outInfo[0]
        val h = outInfo[1]
        val format = outInfo[2]
        val isHdr = outInfo[3] != 0
        val gamut = outInfo[4]
        if (w <= 0 || h <= 0) return@withContext null
        val f16 = format == 1
        val bitmap = pixelsToBitmap(pixels, w, h, f16, gamut) ?: return@withContext null
        val boost = outBoost[0].coerceIn(1f, 64f)
        val wide = gamut == 1 || gamut == 2 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.colorSpace?.isWideGamut == true)
        LibDirectResult(
            bitmap = bitmap,
            isHdrContent = isHdr,
            contentHdrBoost = if (isHdr) boost else 1f,
            isWideGamutSource = wide,
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
     * @param f16 true → [Bitmap.Config.RGBA_F16] linear scRGB
     * @param gamut 0=BT.709/scRGB, 1=Display P3 (gamma 8888), 2=BT.2100
     */
    private fun pixelsToBitmap(pixels: ByteArray, w: Int, h: Int, f16: Boolean, gamut: Int): Bitmap? {
        return runCatching {
            val config = if (f16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.Config.RGBA_F16
            } else {
                Bitmap.Config.ARGB_8888
            }
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val cs = when {
                    f16 -> ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                    gamut == 1 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                    else -> ColorSpace.get(ColorSpace.Named.SRGB)
                }
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
