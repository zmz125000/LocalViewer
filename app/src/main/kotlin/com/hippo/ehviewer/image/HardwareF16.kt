package com.hippo.ehviewer.image

import android.graphics.Bitmap
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
