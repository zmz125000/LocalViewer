package com.hippo.ehviewer.coil

import android.os.Build
import androidx.compose.ui.unit.IntRect
import coil3.BitmapImage
import coil3.Image
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware

data class BitmapImageWithExtraInfo(
    val image: BitmapImage,
    val rect: IntRect = IntRect(0, 0, image.width, image.height),
    val hasQrCode: Boolean = false,
    /** API 34+ Ultra HDR / gain-map attached to [image.bitmap]. */
    val hasGainmap: Boolean = false,
) : Image by image

fun BitmapImage.detectGainmap(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()

object MapExtraInfoInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        val needMap = with(chain.request) { maybeCropBorder || detectQrCode || !allowHardware }
        if (needMap && result is SuccessResult) {
            val image = result.image
            if (image is BitmapImage) {
                return result.copy(
                    image = BitmapImageWithExtraInfo(
                        image = image,
                        hasGainmap = image.detectGainmap(),
                    ),
                )
            }
        }
        return result
    }
}
