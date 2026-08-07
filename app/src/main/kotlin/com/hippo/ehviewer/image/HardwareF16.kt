package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Half
import com.ehviewer.core.util.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrap software [Bitmap.Config.RGBA_F16] into a GPU-sampled HARDWARE bitmap via
 * [HardwareBuffer.RGBA_FP16], preserving [Bitmap.getColorSpace].
 *
 * Shared by [com.hippo.ehviewer.image.hdr.LibDirectDecode] and platform high-depth Coil path.
 * On failure returns null and leaves [software] owned by the caller.
 *
 * [Bitmap.wrapHardwareBuffer] retains its own native ref; always [HardwareBuffer.close] the
 * create() acquisition (same pattern as [com.hippo.ehviewer.coil.CropBorderInterceptor]).
 */
fun tryHardwareF16Wrap(software: Bitmap): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    if (software.config != Bitmap.Config.RGBA_F16) return null
    return runCatching {
        val w = software.width
        val h = software.height
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_RARELY
        val buffer = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_FP16, 1, usage)
        try {
            copyBitmapToAHB(software, buffer, 0, 0)
            val hw = Bitmap.wrapHardwareBuffer(buffer, software.colorSpace)
                ?: error("wrapHardwareBuffer returned null")
            software.recycle()
            hw
        } finally {
            buffer.close()
        }
    }.onFailure { logcat("HardwareF16", it) }.getOrNull()
}

/**
 * Align platform HBD (BitmapFactory F16) with lib-direct-style present encoding:
 * **linear** half-float, tagged with a **linear** color space that keeps **source primaries**.
 *
 * BitmapFactory preferred-F16 is usually still **gamma-encoded**. We apply the source EOTF
 * (or sRGB EOTF if untagged) then re-tag:
 * - sRGB / non-wide → [ColorSpace.Named.LINEAR_EXTENDED_SRGB] (same as JXL SDR F16)
 * - wide gamut (Display P3, BT.2020, ICC) → **same primaries + white point**, linear transfer
 *
 * Never force LINEAR_EXTENDED_SRGB on BT.2020/P3 pixels — that reinterprets wide primaries
 * as BT.709 and shifts hues (grayscale HBD still looks fine; WCG test charts break).
 *
 * On success recycles [software] when a new bitmap is created. On failure returns [software].
 */
fun normalizePlatformHbdToLibDirectF16(software: Bitmap): Bitmap {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return software
    if (software.config != Bitmap.Config.RGBA_F16) return software
    val srcCs = software.colorSpace
    val dstCs = linearPresentColorSpace(srcCs)
    // Already linear in the target space — keep (still may AHB-wrap later).
    if (srcCs != null && srcCs == dstCs) return software
    if (srcCs is ColorSpace.Rgb && isApproximatelyLinearTransfer(srcCs) &&
        samePrimaries(srcCs, dstCs)
    ) {
        // Linear pixels already; only re-tag if needed for a named / clean CS.
        return retagF16(software, dstCs) ?: software
    }

    return runCatching {
        val w = software.width
        val h = software.height
        val bytes = w * h * 8
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        software.copyPixelsToBuffer(buf)
        buf.rewind()

        // Source EOTF (BT.2020 / P3 / sRGB each use their curve); untagged → IEC sRGB.
        val eotf: (Float) -> Float = when {
            srcCs is ColorSpace.Rgb && !isApproximatelyLinearTransfer(srcCs) ->
                { e -> srcCs.eotf.applyAsDouble(e.toDouble()).toFloat() }
            srcCs is ColorSpace.Rgb -> { e -> e } // already linear samples
            else -> ::srgbEotf
        }

        val out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        var i = 0
        while (i < bytes) {
            val r = eotf(Half.toFloat(buf.getShort(i)))
            val g = eotf(Half.toFloat(buf.getShort(i + 2)))
            val b = eotf(Half.toFloat(buf.getShort(i + 4)))
            val a = Half.toFloat(buf.getShort(i + 6))
            out.putShort(i, Half.toHalf(r))
            out.putShort(i + 2, Half.toHalf(g))
            out.putShort(i + 4, Half.toHalf(b))
            out.putShort(i + 6, Half.toHalf(a))
            i += 8
        }
        out.rewind()

        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, dstCs)
        dst.copyPixelsFromBuffer(out)
        dst.prepareToDraw()
        software.recycle()
        dst
    }.onFailure { logcat("HardwareF16", it) }.getOrDefault(software)
}

/**
 * Linear present CS for HBD F16: keep wide-gamut primaries; sRGB family → extended linear sRGB.
 */
private fun linearPresentColorSpace(srcCs: ColorSpace?): ColorSpace {
    val linearSrgb = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    if (srcCs !is ColorSpace.Rgb) return linearSrgb
    // sRGB / scRGB family (including EXTENDED_SRGB gamma)
    if (srcCs.isSrgb || !srcCs.isWideGamut) return linearSrgb
    if (isApproximatelyLinearTransfer(srcCs)) return srcCs
    // Display P3 / BT.2020 / ICC: same primaries + white point, identity transfer.
    val minV = minOf(srcCs.getMinValue(0), 0f)
    val maxV = maxOf(srcCs.getMaxValue(0), 1f)
    return ColorSpace.Rgb(
        "${srcCs.name}-Linear",
        srcCs.primaries,
        srcCs.whitePoint,
        { x: Double -> x },
        { x: Double -> x },
        minV,
        maxV,
    )
}

private fun isApproximatelyLinearTransfer(rgb: ColorSpace.Rgb): Boolean {
    // Identity EOTF: f(0.5) ≈ 0.5 (gamma ~2.2 gives ~0.22).
    val mid = rgb.eotf.applyAsDouble(0.5)
    return kotlin.math.abs(mid - 0.5) < 0.03
}

private fun samePrimaries(a: ColorSpace, b: ColorSpace): Boolean {
    if (a !is ColorSpace.Rgb || b !is ColorSpace.Rgb) return a == b
    val pa = a.primaries
    val pb = b.primaries
    if (pa.size != pb.size) return false
    for (i in pa.indices) {
        if (kotlin.math.abs(pa[i] - pb[i]) > 1e-3f) return false
    }
    return true
}

/** Copy F16 pixels into a new bitmap with [dstCs] (no sample transform). */
private fun retagF16(software: Bitmap, dstCs: ColorSpace): Bitmap? = runCatching {
    if (software.colorSpace == dstCs) return software
    val w = software.width
    val h = software.height
    val bytes = w * h * 8
    val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    software.copyPixelsToBuffer(buf)
    buf.rewind()
    val dst = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, dstCs)
    dst.copyPixelsFromBuffer(buf)
    dst.prepareToDraw()
    software.recycle()
    dst
}.onFailure { logcat("HardwareF16", it) }.getOrNull()

/** IEC 61966-2-1 sRGB EOTF (encoded [0,1] → linear). */
private fun srgbEotf(s: Float): Float {
    if (!s.isFinite() || s <= 0f) return 0f
    if (s >= 1f) return 1f
    return if (s <= 0.04045f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
