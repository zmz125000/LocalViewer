package com.hippo.ehviewer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Aves-style window HDR color mode for Ultra HDR / gain-map images.
 *
 * Enable while any composed / near-visible page has a gain map (reader compose
 * window, not only the focused page). Clear on leave. Avoid flipping mode on
 * every adjacent SDR page — that causes mixed-content brightness thrash.
 *
 * Panel boost ([HdrDisplayInfo]) is applied at **display** time only
 * ([Window.setDesiredHdrHeadroom] on API 35+). Never put panel boost into encode metadata.
 */
private const val TAG = "HdrWindow"

/** Last applied headroom per window identity; skip no-op setDesiredHdrHeadroom. */
private val lastDesiredHeadroom = mutableMapOf<Int, Float>()

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
    val targetMode = if (enable) {
        ActivityInfo.COLOR_MODE_HDR
    } else {
        ActivityInfo.COLOR_MODE_DEFAULT
    }
    // Skip redundant setColorMode — each flip can re-trigger surface brightness ramps.
    if (window.colorMode != targetMode) {
        window.colorMode = targetMode
    }
    applyDesiredHdrHeadroom(enable, contentBoost)
}

/**
 * Request headroom for composed HDR content: min(content peak, panel max boost).
 * API 35+ [android.view.Window.setDesiredHdrHeadroom]; no-op earlier.
 */
private fun Activity.applyDesiredHdrHeadroom(enable: Boolean, contentBoost: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val key = System.identityHashCode(window)
    try {
        if (!enable) {
            if (lastDesiredHeadroom[key] != 0f) {
                window.setDesiredHdrHeadroom(0f)
                lastDesiredHeadroom[key] = 0f
            }
            return
        }
        val displayBoost = HdrDisplayInfo.maxDisplayBoost(displayOrNull())
        val content = contentBoost.coerceIn(1f, 64f)
        val headroom = minOf(content, displayBoost).coerceAtLeast(1f)
        if (lastDesiredHeadroom[key] == headroom) return
        window.setDesiredHdrHeadroom(headroom)
        lastDesiredHeadroom[key] = headroom
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
    ContextCompat.getDisplayOrDefault(this)
