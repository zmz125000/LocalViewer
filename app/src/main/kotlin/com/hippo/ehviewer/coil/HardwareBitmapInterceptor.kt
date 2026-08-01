package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import coil3.Extras
import coil3.asImage
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.intercept.Interceptor.Chain
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware

private val hardwareThresholdKey = Extras.Key(default = 16384)

fun ImageRequest.Builder.hardwareThreshold(size: Int) = apply {
    extras[hardwareThresholdKey] = size
}

val ImageRequest.hardwareThreshold: Int
    get() = getExtra(hardwareThresholdKey)

// minSdk 32: HARDWARE bitmaps always available.
object HardwareBitmapInterceptor : Interceptor {
    override suspend fun intercept(chain: Chain): ImageResult {
        val result = chain.proceed()
        val request = result.request
        if (!request.allowHardware && result is SuccessResult) {
            val image = result.image
            if (image is BitmapImageWithExtraInfo) {
                // Bitmap.copy drops gain maps — keep software bitmap for Ultra HDR.
                if (image.hasGainmap) return result
                val bitmap = image.image.bitmap
                val isHardware = bitmap.config == Bitmap.Config.HARDWARE
                // Large hardware bitmaps have rendering issues (e.g. crash, empty) on some devices.
                // This is not ideal but I haven't figured out how to probe the threshold.
                // All we know is that it's less than the maximum texture size.
                if (!isHardware && maxOf(bitmap.width, bitmap.height) <= request.hardwareThreshold) {
                    bitmap.copy(Bitmap.Config.HARDWARE, false)?.let { hwBitmap ->
                        bitmap.recycle()
                        return result.copy(image = image.copy(image = hwBitmap.asImage()))
                    }
                }
            }
        }
        return result
    }
}
