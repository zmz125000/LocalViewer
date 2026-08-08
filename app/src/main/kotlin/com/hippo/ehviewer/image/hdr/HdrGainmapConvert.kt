package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.RequiresApi
import com.ehviewer.core.util.isAtLeastU

/**
 * Gain-map metadata helpers for Ultra HDR presentation.
 *
 * **Do not rewrite** [Gainmap.displayRatioForFullHdr] after decode. That field is an
 * independent tone-map instruction (HDRCapacityMax in Ultra HDR). Lowering it increases
 * Android's apply weight `W = log(headroom) / log(displayRatioForFullHdr)`, which lifts
 * near-blacks on original UHDR JPEG / gain-map AVIF compared to Chrome and the system
 * gallery (which leave metadata alone).
 *
 * Oversized capacity is an encode-time concern ([LibDirectDecode] / libultrahdr target
 * peak). Display path only **reads** content peak for optional headroom reporting.
 *
 * @see <a href="https://developer.android.com/reference/android/graphics/Gainmap">Gainmap</a>
 * @see <a href="https://developer.android.com/media/platform/hdr-image-format">Ultra HDR</a>
 */
object HdrGainmapConvert {
    /**
     * Content peak boost from gain-map [Gainmap.ratioMax] (linear), read-only.
     * Prefer max(ratioMax); include [Gainmap.displayRatioForFullHdr] only when it is
     * already content-sized (&lt; 20×), never clamp or reattach the gain map.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun contentPeakBoost(gm: Gainmap): Float {
        val maxArr = gm.ratioMax
        var m = 1f
        for (v in maxArr) {
            if (v.isFinite() && v > m) m = v
        }
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
