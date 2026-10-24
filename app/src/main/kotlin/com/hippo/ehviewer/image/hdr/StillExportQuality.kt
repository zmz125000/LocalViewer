package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.Settings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One immutable quality choice per conversion or queued folder batch. */
internal class StillExportQuality(val value: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StillExportQuality> {
        fun current() = StillExportQuality(Settings.imageConversionQuality.value.coerceIn(0, 3))
    }
}
