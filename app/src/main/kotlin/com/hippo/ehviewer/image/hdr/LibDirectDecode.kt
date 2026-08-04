package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import com.ehviewer.core.files.read
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.copyBitmapToAHB
import com.hippo.ehviewer.jni.decodeAvifBytesToDirect
import com.hippo.ehviewer.jni.decodeJxlBytesToDirect
import com.hippo.ehviewer.jni.decodeJxrBytesToDirect
import java.nio.ByteBuffer
import java.util.function.DoubleUnaryOperator
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
     * Bitmap is wide-gamut (Display P3 / BT.2020) after pack.
     * Used with advanced color for [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT].
     */
    val isWideGamutSource: Boolean,
)

/**
 * Decode JXR / JXL / PQ-AVIF straight to a display [Bitmap] for the experimental
 * reader present mode ([com.hippo.ehviewer.Settings.readerLibDirectBitmap]).
 *
 * ## Color management (advanced color on)
 * - **SDR Display P3:** keep P3 → [ColorSpace.Named.DISPLAY_P3] 8888 + WCG window
 * - **HDR BT.2100:** keep BT.2020 linear F16 → custom linear-BT2020 (or named PQ/HLG hint)
 * - **SDR BT.709:** F16 [LINEAR_EXTENDED_SRGB] (high bit depth)
 * - Optional [HardwareBuffer] wrap for F16 (GPU-sampled)
 *
 * Advanced off: rematrix wide → scRGB/sRGB (safe default).
 */
object LibDirectDecode {
    // Same usage flags as CropBorderInterceptor hardware crop path.
    private const val USAGE_HW =
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_RARELY

    /**
     * BT.2020 primaries (CIE xy) for linear extended ColorSpace.
     * Pixels are linear relative to 203 nits (may exceed 1.0). Named
     * [ColorSpace.Named.BT2020_PQ] / HLG expect transfer-encoded values; we stay linear.
     */
    private val bt2020LinearExtended: ColorSpace by lazy {
        ColorSpace.Rgb(
            "BT2020-Linear-Extended",
            floatArrayOf(
                0.708f, 0.292f,
                0.170f, 0.797f,
                0.131f, 0.046f,
            ),
            ColorSpace.ILLUMINANT_D65,
            DoubleUnaryOperator { x -> x },
            DoubleUnaryOperator { x -> x },
            0.0f,
            64.0f,
        )
    }

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
        val outInfo = IntArray(6)
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
        val transfer = outInfo[5]
        if (w <= 0 || h <= 0) return@withContext null
        val f16 = format == 1
        val colorSpace = resolveColorSpace(f16, gamut, transfer)
        val software = pixelsToSoftwareBitmap(pixels, w, h, f16, colorSpace) ?: return@withContext null
        val bitmap = if (advanced && f16) {
            tryHardwareBitmap(software) ?: software
        } else {
            software
        }
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
     * @param gamut 0=BT.709/scRGB, 1=Display P3, 2=BT.2100 (linear in buffer)
     * @param transferCICP 16=PQ, 18=HLG (metadata; buffer is still linear)
     */
    /**
     * @param transferCICP 16=PQ / 18=HLG when source was absolute HDR (pixels remain linear).
     */
    private fun resolveColorSpace(f16: Boolean, gamut: Int, transferCICP: Int): ColorSpace? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        // transferCICP retained for diagnostics / future PQ-encoded pack (Named.BT2020_PQ).
        @Suppress("UNUSED_VARIABLE")
        val tf = transferCICP
        return when {
            // Linear F16 in BT.2020 primaries (advanced HDR preserve).
            f16 && gamut == 2 -> bt2020LinearExtended
            f16 -> ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            gamut == 1 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            else -> ColorSpace.get(ColorSpace.Named.SRGB)
        }
    }

    private fun pixelsToSoftwareBitmap(
        pixels: ByteArray,
        w: Int,
        h: Int,
        f16: Boolean,
        colorSpace: ColorSpace?,
    ): Bitmap? = runCatching {
        val config = if (f16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap.Config.RGBA_F16
        } else {
            Bitmap.Config.ARGB_8888
        }
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && colorSpace != null) {
            Bitmap.createBitmap(w, h, config, true, colorSpace)
        } else {
            Bitmap.createBitmap(w, h, config)
        }
        val expected = if (config == Bitmap.Config.RGBA_F16) w * h * 8 else w * h * 4
        if (pixels.size < expected) {
            bitmap.recycle()
            return@runCatching null
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, expected))
        bitmap.prepareToDraw()
        bitmap
    }.getOrNull()

    /**
     * GPU-friendly wrap via [HardwareBuffer] + [Bitmap.wrapHardwareBuffer], preserving ColorSpace.
     * Falls back to the software bitmap on failure (caller keeps ownership of [software] then).
     */
    private fun tryHardwareBitmap(software: Bitmap): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (software.config != Bitmap.Config.RGBA_F16) return null
        return runCatching {
            val w = software.width
            val h = software.height
            val buffer = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_FP16, 1, USAGE_HW)
            try {
                copyBitmapToAHB(software, buffer, 0, 0)
                val hw = Bitmap.wrapHardwareBuffer(buffer, software.colorSpace)
                    ?: error("wrapHardwareBuffer returned null")
                software.recycle()
                hw
            } catch (t: Throwable) {
                buffer.close()
                throw t
            }
        }.onFailure { logcat(it) }.getOrNull()
    }
}
