package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.os.Build
import com.hippo.ehviewer.Settings

/**
 * Software bitmaps the reader built itself (indexed PDF, JXL/JXR/JPEG 2000/PQ-AVIF).
 * Always uploaded to the GPU. [Settings.readerHardwareBitmap] only skips Coil QR/crop
 * by decoding straight to hardware; it does not leave these bitmaps in software.
 * Coil stills already become [Bitmap.Config.HARDWARE] in [com.hippo.ehviewer.coil.HardwareBitmapInterceptor].
 *
 * When [retainSource] is true the software bitmap is left alive so a WebP encode can
 * still read it. Otherwise a successful GPU copy recycles the software bitmap.
 * F16 uses the FP16 hardware-buffer wrap; [Bitmap.copy] to HARDWARE would quantize it.
 */
fun Bitmap.presentForReader(retainSource: Boolean = false): Bitmap {
    if (config == Bitmap.Config.HARDWARE) return this
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasGainmap()) return this
    val cfg = config
    if (cfg == Bitmap.Config.RGBA_F16) {
        if (retainSource) return this
        return tryHardwareF16Wrap(this) ?: this
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cfg == Bitmap.Config.RGBA_1010102) {
        return this
    }
    if (maxOf(width, height) > Settings.hardwareBitmapThreshold.value) return this
    val hw = copy(Bitmap.Config.HARDWARE, false) ?: return this
    if (!retainSource) recycle()
    return hw
}
