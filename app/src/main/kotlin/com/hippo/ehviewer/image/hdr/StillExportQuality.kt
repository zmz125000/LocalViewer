package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.Settings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One immutable quality choice per conversion or queued folder batch. */
internal class StillExportQuality(
    val value: Int,
    val jxlDistance: Float,
    val codecQuality: Int,
    val jpegTranscoding: Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StillExportQuality> {
        fun current() = StillExportQuality(
            Settings.imageConversionQuality.value.coerceIn(0, 4),
            Settings.imageConversionJxlDistance.value.coerceIn(0, 300) / 100f,
            Settings.imageConversionCustomQuality.value.coerceIn(1, 100),
            Settings.imageConversionJxlJpegTranscoding.value,
        )
    }
}
