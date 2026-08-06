package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.Extras
import coil3.asImage
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.intercept.Interceptor.Chain
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult

private val hardwareThresholdKey = Extras.Key(default = 16384)

fun ImageRequest.Builder.hardwareThreshold(size: Int) = apply {
    extras[hardwareThresholdKey] = size
}

val ImageRequest.hardwareThreshold: Int
    get() = getExtra(hardwareThresholdKey)

/**
 * Prefer a GPU [Bitmap.Config.HARDWARE] buffer when possible.
 *
 * Runs after decode / crop / QR so those steps can still use software pixels.
 * Covers both:
 * - **Software path** (`allowHardware=false`): primary way to get HARDWARE after processing
 * - **Hardware-direct path** (`allowHardware=true`): late upgrade when Coil still returned
 *   software (size policy, format quirks, OEM fallback) — previously left stuck on software
 *
 * [Bitmap.copy] drops gain maps — Ultra HDR stays software.
 * Large bitmaps over [hardwareThreshold] stay software (device texture limits).
 */
// minSdk 32: HARDWARE bitmaps always available.
object HardwareBitmapInterceptor : Interceptor {
    override suspend fun intercept(chain: Chain): ImageResult {
        val result = chain.proceed()
        if (result !is SuccessResult) return result
        val request = result.request
        return when (val image = result.image) {
            is BitmapImageWithExtraInfo -> {
                // Bitmap.copy drops gain maps — keep software bitmap for Ultra HDR.
                if (image.hasGainmap) return result
                val hw = tryUpgradeToHardware(image.image.bitmap, request) ?: return result
                result.copy(image = image.copy(image = hw.asImage()))
            }
            is BitmapImage -> {
                // hardware-direct requests often skip MapExtraInfo; still upgrade software fallbacks.
                if (image.detectGainmap()) return result
                val hw = tryUpgradeToHardware(image.bitmap, request) ?: return result
                result.copy(image = hw.asImage())
            }
            else -> result
        }
    }

    /**
     * @return new HARDWARE bitmap (caller owns; original recycled), or null if no upgrade
     */
    private fun tryUpgradeToHardware(bitmap: Bitmap, request: ImageRequest): Bitmap? {
        if (bitmap.config == Bitmap.Config.HARDWARE) return null
        // Large hardware bitmaps have rendering issues (e.g. crash, empty) on some devices.
        // This is not ideal but I haven't figured out how to probe the threshold.
        // All we know is that it's less than the maximum texture size.
        if (maxOf(bitmap.width, bitmap.height) > request.hardwareThreshold) return null
        val hw = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return null
        bitmap.recycle()
        return hw
    }
}
