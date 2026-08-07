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
 * Desired HDR headroom stays automatic (`setDesiredHdrHeadroom(0)`) so gain-map
 * weight matches Chrome / system gallery. Never put panel boost into encode metadata.
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
 * @param contentBoost unused for headroom (kept for call-site compatibility)
 */
fun Activity.setHdrColorMode(on: Boolean, contentBoost: Float = 1f) {
    setReaderColorMode(hdr = on, contentBoost = contentBoost, wideColor = false)
}

/**
 * Reader color mode: HDR wins over WCG when both requested.
 *
 * @param hdr enable [ActivityInfo.COLOR_MODE_HDR] when display supports HDR
 * @param contentBoost unused; headroom is automatic on API 35+
 * @param wideColor enable [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT] when not HDR
 *   and the display is wide-gamut (Android WCG is opt-in)
 */
fun Activity.setReaderColorMode(
    hdr: Boolean,
    contentBoost: Float = 1f,
    wideColor: Boolean = false,
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
    if (window.colorMode != targetMode) {
        window.colorMode = targetMode
        val displayOk = displayOrNull()?.isWideColorGamut == true
        val configOk = resources.configuration.isScreenWideColorGamut
        Log.d(
            TAG,
            "colorMode=$targetMode hdr=$enableHdr wcg=$enableWcg " +
                "(wantWide=$wideColor capable=$wcgCapable display=$displayOk config=$configOk)",
        )
        if (wideColor && !enableHdr && !wcgCapable) {
            Log.w(TAG, "WCG requested but display/config report no wide color gamut")
        }
    }
    applyDesiredHdrHeadroom(enableHdr, contentBoost)
}

/**
 * HDR headroom on API 35+: leave **automatic** (`0f`), matching Chrome / system gallery.
 *
 * Forcing min(content peak, panel boost) via [Window.setDesiredHdrHeadroom] can over-apply
 * the gain map and lift near-blacks on UHDR JPEG / gain-map AVIF. Documented default is
 * automatic selection from panel + ambient conditions.
 *
 * [contentBoost] is retained for API compatibility / logging only (not applied).
 * Presentation still relies on [ActivityInfo.COLOR_MODE_HDR].
 */
private fun Activity.applyDesiredHdrHeadroom(enable: Boolean, contentBoost: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val key = System.identityHashCode(window)
    try {
        // 0f = automatic headroom (do not force content/panel boost).
        if (lastDesiredHeadroom[key] != 0f) {
            window.setDesiredHdrHeadroom(0f)
            lastDesiredHeadroom[key] = 0f
            Log.d(
                TAG,
                "desiredHdrHeadroom=auto(0) enable=$enable contentBoost=$contentBoost",
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
