package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.Settings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One immutable compression choice per conversion or queued folder batch. */
internal class StillExportCompression(val enabled: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StillExportCompression> {
        fun current() = StillExportCompression(Settings.imageConversionCompression.value)
    }
}
