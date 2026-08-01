package com.hippo.ehviewer.util

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.ehviewer.core.util.isAtLeastU

/**
 * Live panel HDR capability (display-time only — never bake into Ultra HDR encode metadata).
 *
 * | API | Source |
 * |-----|--------|
 * | 34+ | [Display.isHdrSdrRatioAvailable] / [Display.getHdrSdrRatio] |
 * | 36+ | [Display.getHighestHdrSdrRatio] when present |
 * | 24+ | [Display.HdrCapabilities] max luminance / 203 as fallback |
 */
object HdrDisplayInfo {
    /** Conservative default when nothing is advertised (typical phone HDR boost). */
    const val DEFAULT_BOOST = 4.0f

    /**
     * Maximum HDR/SDR luminance ratio the panel can apply right now (or highest known).
     * Always ≥ 1. Used for window headroom and gain-map weight understanding.
     */
    fun maxDisplayBoost(display: Display?): Float {
        if (display == null) return DEFAULT_BOOST
        if (isAtLeastU) {
            val live = liveHdrSdrRatio(display)
            if (live != null) return live.coerceAtLeast(1f)
        }
        val fromCaps = boostFromHdrCapabilities(display)
        if (fromCaps != null) return fromCaps
        return if (display.isHdr) DEFAULT_BOOST else 1f
    }

    fun Activity.maxDisplayBoost(): Float = maxDisplayBoost(displayOrNull())

    private fun Activity.displayOrNull(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun liveHdrSdrRatio(display: Display): Float? {
        // API 36+: preferred “highest” when available (reflective — may not be on all SDKs).
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val m = Display::class.java.getMethod("getHighestHdrSdrRatio")
                val v = (m.invoke(display) as? Number)?.toFloat()
                if (v != null && v.isFinite() && v >= 1f) return v
            }
        }
        return runCatching {
            if (!display.isHdrSdrRatioAvailable) return@runCatching null
            val r = display.hdrSdrRatio
            if (r.isFinite() && r >= 1f) r else null
        }.getOrNull()
    }

    private fun boostFromHdrCapabilities(display: Display): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val caps = display.hdrCapabilities ?: return null
        val maxNits = caps.desiredMaxLuminance
        if (!maxNits.isFinite() || maxNits <= 0f) return null
        // Ultra HDR reference white ≈ 203 nits.
        return (maxNits / 203f).coerceIn(1f, 64f)
    }
}
