package com.hippo.ehviewer.image.hdr

import kotlin.math.pow

/**
 * How a camera RAW frame is packed.
 *
 * [Hdr] is deep color plus Android 16 HDR ([Settings.readerHdrDisplay] on and the
 * panel can show HDR). While that switch is on, [Settings.readerAdvancedColor] is
 * ignored: the bitmap stays float16, and a non-HDR panel keeps deep color without
 * requesting a HDR window.
 *
 * Ordinals match the native `present` argument: 0 eight-bit, 1 deep color, 2 HDR.
 */
enum class RawPresent {
    EightBit,
    DeepColor,
    Hdr,
}

fun rawPresentMode(hdrDisplay: Boolean, advancedColor: Boolean, panelHdr: Boolean): RawPresent = when {
    hdrDisplay && panelHdr -> RawPresent.Hdr
    hdrDisplay || advancedColor -> RawPresent.DeepColor
    else -> RawPresent.EightBit
}

fun rawIsHdrContent(mode: RawPresent): Boolean = mode == RawPresent.Hdr

fun rawIsWideGamut(mode: RawPresent): Boolean = mode != RawPresent.EightBit

/**
 * Linear peak above diffuse white, capped by the live panel boost.
 * Non-HDR modes stay at 1. A HDR frame with no highlight headroom also stays at 1.
 */
fun rawContentBoost(mode: RawPresent, peakOverWhite: Float, panelBoost: Float): Float {
    if (mode != RawPresent.Hdr) return 1f
    val peak = if (peakOverWhite.isFinite() && peakOverWhite > 0f) peakOverWhite else 1f
    val panel = if (panelBoost.isFinite()) panelBoost.coerceIn(1f, 64f) else 1f
    return peak.coerceAtLeast(1f).coerceAtMost(panel)
}

/**
 * LibRaw 16-bit sample divided by sensor white (full scale).
 *
 * 1.0 is that white for both deep color and HDR. [exposureEv] is stops on top,
 * including DNG BaselineExposure. There is no per-frame percentile stretch.
 * [highlightStops] compresses only samples above 1, then the result is clamped to [cap].
 *
 * The native pack in `raw_still.cpp` must use this same formula.
 */
fun rawLinearSample(
    sceneOverWhite: Float,
    exposureEv: Float,
    highlightStops: Float,
    cap: Float,
): Float {
    if (!sceneOverWhite.isFinite() || !exposureEv.isFinite() || !highlightStops.isFinite() || !cap.isFinite()) {
        return 0f
    }
    if (sceneOverWhite <= 0f) return 0f
    val base = sceneOverWhite * 2.0.pow(exposureEv.toDouble()).toFloat()
    val y = if (highlightStops <= 0f || base <= 1f) {
        base
    } else {
        1f + (base - 1f) * 2.0.pow((-highlightStops).toDouble()).toFloat()
    }
    if (!y.isFinite() || y <= 0f) return 0f
    return y.coerceAtMost(cap.coerceAtLeast(1f))
}
