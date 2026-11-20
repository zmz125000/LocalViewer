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
    val avifBitDepth: Int = 10,
    val downsizeGainMap: Boolean = false,
    val extendedSrgbBt2020: Boolean = true,
    val keepExif: Boolean = false,
    float32: Boolean = false,
    val srgbClamp: Boolean = false,
    val toneMapping: SdrToneMapping = SdrToneMapping(mapper = 0),
) : AbstractCoroutineContextElement(Key) {
    /** Custom-only; captured with the job so later setting changes cannot change its buffers. */
    val float32Processing = value == 4 && float32

    companion object Key : CoroutineContext.Key<StillExportQuality> {
        fun current() = StillExportQuality(
            Settings.imageConversionQuality.value.coerceIn(0, 4),
            Settings.imageConversionJxlDistance.value.coerceIn(0, 300) / 100f,
            Settings.imageConversionCustomQuality.value.coerceIn(1, 100),
            Settings.imageConversionJxlJpegTranscoding.value,
            if (Settings.imageConversionAvif12Bit.value) 12 else 10,
            Settings.imageConversionDownsizeGainMap.value,
            Settings.imageConversionExtendedSrgbBt2020.value && !Settings.imageConversionSrgbClamp.value,
            Settings.imageConversionKeepExif.value,
            Settings.imageConversionFloat32.value,
            Settings.imageConversionSrgbClamp.value,
            SdrToneMapping.current(),
        )
    }
}

/** Captured with the export batch, independent of later settings changes. */
internal data class SdrToneMapping(
    val mapper: Int = 2,
    val shoulder: Float = .50f,
    val rangeNits: Float = 10000f,
) {
    val enabled: Boolean get() = mapper != 0

    fun nativeControls() = floatArrayOf(mapper.toFloat(), shoulder, rangeNits)

    companion object {
        fun current() = SdrToneMapping(
            Settings.imageConversionToneMapper.value.coerceIn(0, 3),
            Settings.imageConversionToneShoulder.value.coerceIn(0, 95) / 100f,
            Settings.imageConversionToneRange.value.coerceIn(204, 10000).toFloat(),
        )
    }
}
