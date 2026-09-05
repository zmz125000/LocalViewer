package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import com.ehviewer.core.files.read
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.tryHardwareF16FromPixels
import com.hippo.ehviewer.jni.decodeAvifBytesToDirect
import com.hippo.ehviewer.jni.decodeJxlBytesToDirect
import com.hippo.ehviewer.jni.decodeJxrBytesToDirect
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * - **Display P3:** keep linear P3 in RGBA_F16 + WCG window
 * - **BT.2020:** keep linear BT.2020 in RGBA_F16 + WCG/HDR window as appropriate
 * - **SDR BT.709:** F16 [LINEAR_EXTENDED_SRGB] (high bit depth)
 * - Optional [HardwareBuffer] wrap for F16 (GPU-sampled)
 *
 * Advanced off: rematrix wide → scRGB/sRGB (safe default).
 */
object LibDirectDecode {
    /**
     * Full-res RGBA_F16 is ~66 MiB at 3500×2500. Concurrent packs (PageLoader
     * Semaphore 4 + two pages) blow a 256 MiB Java heap → blocking GC Alloc /
     * SoftReference thrash. Serialize heavy native→Bitmap work process-wide.
     * Also used for platform high-depth F16 frames.
     */
    internal val heavyDecode = Semaphore(1)

    /**
     * ICC type-3 identity (Y = X). [Bitmap.wrapHardwareBuffer] requires this;
     * [DoubleUnaryOperator] identity has no native SkColorSpace and throws
     * "ColorSpace must use an ICC parametric transfer function".
     *
     * Same 5-tuple AOSP uses for gamma=1 named spaces (LINEAR_SRGB).
     * F16 samples may still exceed 1.0 (scene-linear HDR); Skia does not
     * clamp HardwareBuffer pixels to the public 0..1 ColorSpace range.
     */
    private val linearIccTransfer: ColorSpace.Rgb.TransferParameters by lazy {
        ColorSpace.Rgb.TransferParameters(1.0, 0.0, 0.0, 0.0, 1.0)
    }

    /**
     * BT.2020 primaries (CIE xy), linear. Pixels are relative to 203 nits.
     * Named [ColorSpace.Named.BT2020_PQ] / HLG expect transfer-encoded values.
     */
    private val bt2020LinearExtended: ColorSpace by lazy {
        ColorSpace.Rgb(
            "BT2020-Linear-Extended",
            floatArrayOf(
                0.708f,
                0.292f,
                0.170f,
                0.797f,
                0.131f,
                0.046f,
            ),
            ColorSpace.ILLUMINANT_D65,
            linearIccTransfer,
        )
    }

    /** Linear Display P3; matches native gamut=1 F16 samples exactly. */
    private val displayP3LinearExtended: ColorSpace by lazy {
        ColorSpace.Rgb(
            "Display-P3-Linear-Extended",
            floatArrayOf(
                0.680f,
                0.320f,
                0.265f,
                0.690f,
                0.150f,
                0.060f,
            ),
            ColorSpace.ILLUMINANT_D65,
            linearIccTransfer,
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
        // Gate before allocating native F16 + Java byte[] + Bitmap (~one full frame each).
        heavyDecode.withPermit {
            decodeUnlocked(src, fileNameHint, maxEdge)
        }
    }

    private fun decodeUnlocked(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int,
    ): LibDirectResult? {
        // Scope source bytes tightly so they are eligible for GC before Bitmap.create.
        val packed = run {
            val bytes = readBytes(src) ?: return null
            if (bytes.isEmpty()) return null
            val route = classify(bytes, bytes.size, fileNameHint)
            if (route !is StillRoute.Lib) return null
            val advanced = Settings.readerAdvancedColor.value
            val outInfo = IntArray(6)
            val outBoost = FloatArray(1)
            val pixels = when (route.codec) {
                LibCodec.Jxl -> decodeJxlBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                LibCodec.Jxr -> decodeJxrBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                LibCodec.AvifPq -> decodeAvifBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
            } ?: return null
            // [bytes] ends with this block; only packed pixels + meta remain.
            PackedPixels(pixels, outInfo, outBoost, advanced)
        }
        val w = packed.outInfo[0]
        val h = packed.outInfo[1]
        val format = packed.outInfo[2]
        val isHdr = packed.outInfo[3] != 0
        val gamut = packed.outInfo[4]
        val transfer = packed.outInfo[5]
        if (w <= 0 || h <= 0) return null
        val f16 = format == 1
        val colorSpace = resolveColorSpace(f16, gamut, transfer)
        // Default advanced/F16 path: copy the JNI result straight into a HardwareBuffer.
        // This removes the ByteArray → software Bitmap → AHB double copy while preserving
        // the exact linear scRGB/BT.2020 ColorSpace chosen above. Fall back to software on
        // unsupported devices or when the reader hardware-bitmap preference is disabled.
        val hardware = if (packed.advanced && f16 && Settings.readerHardwareBitmap.value) {
            tryHardwareF16FromPixels(packed.pixels, w, h, colorSpace)
        } else {
            null
        }
        val bitmap = hardware
            ?: pixelsToSoftwareBitmap(packed.pixels, w, h, f16, colorSpace)
            ?: return null
        val boost = packed.outBoost[0].coerceIn(1f, 64f)
        val wide = gamut == 1 || gamut == 2 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.colorSpace?.isWideGamut == true)
        return LibDirectResult(
            bitmap = bitmap,
            isHdrContent = isHdr,
            contentHdrBoost = if (isHdr) boost else 1f,
            isWideGamutSource = wide,
        )
    }

    private class PackedPixels(
        val pixels: ByteArray,
        val outInfo: IntArray,
        val outBoost: FloatArray,
        val advanced: Boolean,
    )

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
     * @param transferCICP 16=PQ / 18=HLG when source was absolute HDR (pixels remain linear)
     */
    private fun resolveColorSpace(f16: Boolean, gamut: Int, transferCICP: Int): ColorSpace? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        // transferCICP retained for diagnostics / future PQ-encoded pack (Named.BT2020_PQ).
        @Suppress("UNUSED_VARIABLE")
        val tf = transferCICP
        return when {
            // Linear F16 in Display P3 primaries (advanced WCG/deep color).
            f16 && gamut == 1 -> displayP3LinearExtended
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
}
