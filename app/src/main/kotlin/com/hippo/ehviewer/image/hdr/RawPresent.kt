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
 * LibRaw 16-bit code `scene` (1 = sensor clip) → linear float for the bitmap.
 *
 * 1.0 is paper white. [hdr] with a missing baseline (LibRaw sentinel -999, or any
 * non-finite / out-of-range tag) reserves 2 stops, so the clip lands at 4.
 * Deep color passes [hdr] false and does not invent that headroom.
 * A real DNG BaselineExposure, including 0, is the scale for both.
 * [highlightStops] compresses only samples above 1, then the result is clamped to [cap].
 *
 * The native pack in `raw_still.cpp` must use this same formula.
 */
fun rawLinearSample(
    scene: Float,
    baselineEv: Float,
    exposureEv: Float,
    highlightStops: Float,
    cap: Float,
    hdr: Boolean,
): Float {
    if (!scene.isFinite() || !exposureEv.isFinite() || !highlightStops.isFinite() || !cap.isFinite()) {
        return 0f
    }
    if (scene <= 0f) return 0f
    val beValid = baselineEv.isFinite() && baselineEv > -100f && baselineEv < 10f
    val scale = if (beValid) {
        baselineEv
    } else if (hdr) {
        2f
    } else {
        0f
    }
    val base = scene * 2.0.pow((scale + exposureEv).toDouble()).toFloat()
    val y = if (highlightStops <= 0f || base <= 1f) {
        base
    } else {
        1f + (base - 1f) * 2.0.pow((-highlightStops).toDouble()).toFloat()
    }
    if (!y.isFinite() || y <= 0f) return 0f
    return y.coerceAtMost(cap.coerceAtLeast(1f))
}
