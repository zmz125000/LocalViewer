package com.hippo.ehviewer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi

/**
 * Aves-style window HDR color mode for Ultra HDR / gain-map images.
 * Only enable while an HDR page is on screen; always clear on leave.
 *
 * Panel boost ([HdrDisplayInfo]) is applied at **display** time only
 * ([Window.setDesiredHdrHeadroom] on API 35+). Never put panel boost into encode metadata.
 */
private const val TAG = "HdrWindow"

fun Activity.supportsScreenHdr(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return displayIsHdr() || resources.configuration.isScreenHdr
}

/**
 * @param on enable HDR color mode
 * @param contentBoost content hdr capacity / peak boost (from gain map), for headroom request
 */
fun Activity.setHdrColorMode(on: Boolean, contentBoost: Float = 1f) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val enable = on && supportsScreenHdr()
    window.colorMode = if (enable) {
        ActivityInfo.COLOR_MODE_HDR
    } else {
        ActivityInfo.COLOR_MODE_DEFAULT
    }
    applyDesiredHdrHeadroom(enable, contentBoost)
}

/**
 * Request headroom for the current page: min(content peak, panel max boost).
 * API 35+ [android.view.Window.setDesiredHdrHeadroom]; no-op earlier.
 */
private fun Activity.applyDesiredHdrHeadroom(enable: Boolean, contentBoost: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    try {
        if (!enable) {
            window.setDesiredHdrHeadroom(0f)
            return
        }
        val displayBoost = HdrDisplayInfo.maxDisplayBoost(displayOrNull())
        val content = contentBoost.coerceIn(1f, 64f)
        val headroom = minOf(content, displayBoost).coerceAtLeast(1f)
        window.setDesiredHdrHeadroom(headroom)
        Log.d(
            TAG,
            "desiredHdrHeadroom=$headroom contentBoost=$content displayBoost=$displayBoost",
        )
    } catch (e: Throwable) {
        Log.w(TAG, "setDesiredHdrHeadroom failed", e)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun Activity.displayIsHdr(): Boolean {
    val d: Display? = displayOrNull()
    return d?.isHdr == true
}

private fun Activity.displayOrNull(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
