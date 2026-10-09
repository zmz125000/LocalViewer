package com.hippo.ehviewer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Reader window color mode for Ultra HDR / wide-gamut stills.
 *
 * Priority: **HDR > wide color gamut > default** (single [Window.colorMode] slot).
 *
 * **Option A (advanced color toggle, default on):** while the reader is open and
 * advanced color is enabled, request session WCG so platform decode preserves
 * embedded ICC (sRGB stays tagged sRGB — no oversaturation). Clear on leave.
 * HDR still wins when composed pages need HDR.
 *
 * Gain-map pages keep automatic headroom (`setDesiredHdrHeadroom(0)`) so the map
 * weight matches Chrome / system gallery. A visible lib-direct F16 page with no
 * gain map requests its content boost, which for JXR is the fitted panel ceiling.
 * Never put panel boost into encode metadata.
 *
 * Manifest: [MainActivity] declares `android:colorMode="wideColorGamut"` so the
 * activity surface *can* carry wide color (reader is Compose inside MainActivity).
 * [MainActivity.onCreate] forces [ActivityInfo.COLOR_MODE_DEFAULT] until the reader
 * requests HDR/WCG (avoid whole-app WCG cost).
 *
 * @see <a href="https://developer.android.com/training/wide-color-gamut">Wide color gamut</a>
 */
private const val TAG = "HdrWindow"

/** Last applied headroom per window identity; skip no-op setDesiredHdrHeadroom. */
private val lastDesiredHeadroom = mutableMapOf<Int, Float>()

fun Activity.supportsScreenHdr(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return displayIsHdr() || resources.configuration.isScreenHdr
}

/**
 * True when this device can present wide color.
 *
 * Accepts either [Display.isWideColorGamut] (hardware) **or**
 * [android.content.res.Configuration.isScreenWideColorGamut] — configuration alone
 * can lag / disagree on multi-display Android 14+ and falsely block WCG.
 */
fun Activity.supportsWideColorGamut(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val displayOk = displayOrNull()?.isWideColorGamut == true
    val configOk = resources.configuration.isScreenWideColorGamut
    return displayOk || configOk
}

/**
 * @param on enable HDR color mode
 * @param contentBoost headroom ratio for lib-direct F16. ≤1 keeps automatic headroom.
 */
fun Activity.setHdrColorMode(on: Boolean, contentBoost: Float = 1f) {
    setReaderColorMode(hdr = on, contentBoost = contentBoost, wideColor = false)
}

/**
 * Reader color mode: HDR wins over WCG when both requested.
 *
 * @param hdr enable [ActivityInfo.COLOR_MODE_HDR] when display supports HDR
 * @param contentBoost headroom ratio when this page is lib-direct F16. ≤1, and any
 *   gain-map page, stay on automatic headroom.
 * @param wideColor enable [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT] when not HDR
 *   and the display is wide-gamut (Android WCG is opt-in)
 * @param force re-apply even when [Window.colorMode] already matches. Needed after
 *   orientation [android.content.pm.ActivityInfo.configChanges] — Android recreates
 *   the surface as SDR while the getter can still report the old mode.
 */
fun Activity.setReaderColorMode(
    hdr: Boolean,
    contentBoost: Float = 1f,
    wideColor: Boolean = false,
    force: Boolean = false,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val enableHdr = hdr && supportsScreenHdr()
    val wcgCapable = supportsWideColorGamut()
    val enableWcg = !enableHdr && wideColor && wcgCapable
    val targetMode = when {
        enableHdr -> ActivityInfo.COLOR_MODE_HDR
        enableWcg -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        else -> ActivityInfo.COLOR_MODE_DEFAULT
    }
    // Skip redundant setColorMode — each flip can re-trigger surface brightness ramps.
    // [force] still writes: after rotation the surface is new even if the getter matches.
    if (force || window.colorMode != targetMode) {
        window.colorMode = targetMode
        val displayOk = displayOrNull()?.isWideColorGamut == true
        val configOk = resources.configuration.isScreenWideColorGamut
        Log.d(
            TAG,
            "colorMode=$targetMode hdr=$enableHdr wcg=$enableWcg force=$force " +
                "(wantWide=$wideColor capable=$wcgCapable display=$displayOk config=$configOk)",
        )
        if (wideColor && !enableHdr && !wcgCapable) {
            Log.w(TAG, "WCG requested but display/config report no wide color gamut")
        }
    }
    applyDesiredHdrHeadroom(enableHdr, contentBoost, force)
}

/**
 * HDR headroom on API 35+.
 *
 * Gain-map pages pass [contentBoost] ≤ 1 and stay automatic (`0f`). Forcing a ratio
 * there over-applies the gain map and lifts near-blacks. Lib-direct F16 passes the
 * content peak (JXR: the panel ceiling the highlight tail was fitted to) so the
 * compositor does not clip that tail.
 */
private fun Activity.applyDesiredHdrHeadroom(enable: Boolean, contentBoost: Float, force: Boolean = false) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val key = System.identityHashCode(window)
    val desired = if (enable && contentBoost.isFinite() && contentBoost > 1f) {
        contentBoost.coerceIn(1f, 64f)
    } else {
        0f
    }
    try {
        if (force || lastDesiredHeadroom[key] != desired) {
            window.setDesiredHdrHeadroom(desired)
            lastDesiredHeadroom[key] = desired
            Log.d(
                TAG,
                "desiredHdrHeadroom=$desired enable=$enable force=$force contentBoost=$contentBoost",
            )
        }
    } catch (e: Throwable) {
        Log.w(TAG, "setDesiredHdrHeadroom failed", e)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun Activity.displayIsHdr(): Boolean {
    val d: Display? = displayOrNull()
    return d?.isHdr == true
}

private fun Activity.displayOrNull(): Display? = ContextCompat.getDisplayOrDefault(this)
