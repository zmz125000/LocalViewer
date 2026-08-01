package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ehviewer.core.util.isAtLeastU

/**
 * Post-decode gain-map metadata fixes.
 *
 * ImageDecoder may leave [Gainmap.getDisplayRatioForFullHdr] at an oversized
 * capacity (e.g. ~49 from default LINEAR encode). Android applies:
 *   weight ≈ log(displayBoost) / log(displayRatioForFullHdr)
 * so capacity 49 on a 4× panel → weight ≈ 0.25 (looks SDR).
 *
 * Prefer fixing at **encode** (content-matched capacity). This clamp is a safety net
 * for legacy converts / third-party files.
 */
object HdrGainmapConvert {
    private const val TAG = "HdrGainmap"

    /**
     * Clamp [Gainmap.displayRatioForFullHdr] toward content peak (max of ratioMax).
     * Does not use panel boost — encode/display ratio are separate concerns.
     *
     * @return estimated content peak boost (for window headroom), or 1f if no gain map.
     */
    fun clampOversizedCapacity(bitmap: Bitmap): Float {
        if (!isAtLeastU) return 1f
        return clampOversizedCapacityU(bitmap)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clampOversizedCapacityU(bitmap: Bitmap): Float {
        val gm = bitmap.gainmap ?: return 1f
        val contentPeak = contentPeakBoost(gm)
        val full = gm.displayRatioForFullHdr
        // Oversized capacity vs content: clamp full-HDR ratio down toward content peak.
        if (full.isFinite() && contentPeak.isFinite() && full > contentPeak * 1.25f && contentPeak >= 1.05f) {
            val clamped = contentPeak.coerceIn(1.05f, 64f)
            Log.i(TAG, "clamp displayRatioForFullHdr $full → $clamped (contentPeak=$contentPeak)")
            gm.displayRatioForFullHdr = clamped
            bitmap.gainmap = gm
            return clamped
        }
        return when {
            full.isFinite() && full >= 1f -> full.coerceAtMost(64f)
            contentPeak.isFinite() && contentPeak >= 1f -> contentPeak.coerceAtMost(64f)
            else -> 1f
        }
    }

    /** Content boost from gain-map ratioMax (linear). */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun contentPeakBoost(gm: Gainmap): Float {
        val maxArr = gm.ratioMax
        var m = 1f
        for (v in maxArr) {
            if (v.isFinite() && v > m) m = v
        }
        // Also consider displayRatioForFullHdr when it is already content-sized.
        val full = gm.displayRatioForFullHdr
        if (full.isFinite() && full > 1f && full < 20f && full > m) m = full
        return m.coerceIn(1f, 64f)
    }

    fun contentPeakBoost(bitmap: Bitmap): Float {
        if (!isAtLeastU) return 1f
        val gm = bitmap.gainmap ?: return 1f
        return contentPeakBoost(gm)
    }
}
