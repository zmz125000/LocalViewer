package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.Settings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Immutable RAW adjustments; deliberately independent of the main sensor-decode toggle. */
internal data class RawExportControls(
    val exposure: Float = 0f,
    val whiteBalance: Int = 0,
    val kelvin: Int = 5200,
    val highlightStops: Float = 0f,
    val shoulder: Float = 0.5f,
    val shadows: Float = 0f,
    val midtones: Float = 0f,
    val highlights: Float = 0f,
    val cameraLook: Boolean = true,
    val linearHdr: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RawExportControls> {
        fun current() = RawExportControls(
            exposure = (Settings.readerRawExposure.value / 10f).coerceIn(-3f, 3f),
            whiteBalance = Settings.readerRawWhiteBalance.value.coerceIn(0, 8),
            kelvin = Settings.readerRawKelvin.value.coerceIn(2000, 12000),
            highlightStops = (Settings.readerRawHighlight.value / 10f).coerceIn(0f, 3f),
            shoulder = (Settings.readerRawShoulder.value / 100f).coerceIn(0f, 1f),
            shadows = (Settings.readerRawShadows.value / 100f).coerceIn(-1f, 1f),
            midtones = (Settings.readerRawMidtones.value / 100f).coerceIn(-1f, 1f),
            highlights = (Settings.readerRawHighlights.value / 100f).coerceIn(-1f, 1f),
            cameraLook = Settings.readerRawCameraLook.value,
            linearHdr = Settings.readerRawHdrLinear.value,
        )

        fun capture(target: StillExportTarget) = if (target.rawCustom()) current() else RawExportControls()
    }
}

/** Preserve a batch's snapshot through staging, pickers, queue waits and parallel jobs. */
internal suspend fun <T> withRawExportControls(target: StillExportTarget, work: suspend () -> T): T {
    val context = currentCoroutineContext()
    val raw = context[RawExportControls] ?: RawExportControls.capture(target)
    val compression = context[StillExportCompression] ?: StillExportCompression.current()
    return withContext(raw + compression) { work() }
}
