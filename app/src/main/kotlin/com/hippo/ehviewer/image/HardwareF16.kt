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
 * Align platform HBD (BitmapFactory F16) with lib-direct present encoding:
 * **linear** half-float + [ColorSpace.Named.LINEAR_EXTENDED_SRGB].
 *
 * BitmapFactory preferred-F16 is still usually **gamma-encoded** (often weak / "Unknown"
 * color space). Lib-direct JXL is already linear scRGB — normalize so OEM 10-bit paths match.
 *
 * On success recycles [software] when a new bitmap is created. On failure returns [software].
 */
fun normalizePlatformHbdToLibDirectF16(software: Bitmap): Bitmap {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return software
    if (software.config != Bitmap.Config.RGBA_F16) return software
    val linear = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    val srcCs = software.colorSpace
    // Already lib-direct style — keep (still may AHB-wrap later).
    if (srcCs != null && srcCs == linear) return software

    return runCatching {
        val w = software.width
        val h = software.height
        val bytes = w * h * 8
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        software.copyPixelsToBuffer(buf)
        buf.rewind()

        // Prefer source Rgb EOTF (Display P3 / sRGB share the same curve shape); else IEC sRGB.
        val eotf: (Float) -> Float = when (srcCs) {
            is ColorSpace.Rgb -> { e -> srcCs.eotf.applyAsDouble(e.toDouble()).toFloat() }
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

        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, linear)
        dst.copyPixelsFromBuffer(out)
        dst.prepareToDraw()
        software.recycle()
        dst
    }.onFailure { logcat("HardwareF16", it) }.getOrDefault(software)
}

/** IEC 61966-2-1 sRGB EOTF (encoded [0,1] → linear). */
private fun srgbEotf(s: Float): Float {
    if (!s.isFinite() || s <= 0f) return 0f
    if (s >= 1f) return 1f
    return if (s <= 0.04045f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
