package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import com.ehviewer.core.util.logcat

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
    if (!colorSpaceSupportsHardwareWrap(software.colorSpace)) return null
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
 * Copy packed RGBA_F16 pixels directly into a GPU-sampled [HardwareBuffer], avoiding the
 * intermediate software Bitmap used by the lib-direct path.
 *
 * [colorSpace] describes the samples exactly as packed by the native codec: linear extended
 * sRGB for scRGB, linear BT.2020 for preserved HDR/WCG, or another explicit source space.
 * No transfer or gamut conversion happens here, so deep color and WCG metadata are preserved.
 */
fun tryHardwareF16FromPixels(
    pixels: ByteArray,
    width: Int,
    height: Int,
    colorSpace: ColorSpace?,
): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    if (width <= 0 || height <= 0) return null
    val expected = width.toLong() * height * 8L
    if (expected > Int.MAX_VALUE || pixels.size.toLong() != expected) return null
    if (!colorSpaceSupportsHardwareWrap(colorSpace)) return null
    return runCatching {
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_RARELY
        val buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_FP16, 1, usage)
        try {
            copyByteArrayToAHB(pixels, buffer)
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?: error("wrapHardwareBuffer returned null")
        } finally {
            buffer.close()
        }
    }.onFailure { logcat("HardwareF16", it) }.getOrNull()
}

/**
 * [Bitmap.wrapHardwareBuffer] calls native SkColorSpace creation, which requires
 * a named space or an RGB space with ICC parametric transfer parameters.
 */
private fun colorSpaceSupportsHardwareWrap(colorSpace: ColorSpace?): Boolean {
    if (colorSpace == null) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (colorSpace.id != ColorSpace.MIN_ID) return true
    val rgb = colorSpace as? ColorSpace.Rgb ?: return false
    return rgb.transferParameters != null
}
