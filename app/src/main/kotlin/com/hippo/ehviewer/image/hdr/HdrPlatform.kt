package com.hippo.ehviewer.image.hdr

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import com.ehviewer.core.util.isAtLeastU

/**
 * API layering for HDR image display / optional platform features.
 *
 * | Level | Use |
 * |-------|-----|
 * | API 34 (U) | Floor: [android.graphics.Bitmap.hasGainmap], window HDR, Ultra HDR JPEG v1 |
 * | API 35 (V) | Prefer ISO 21496-1 when platform exposes dual metadata |
 * | API 36+    | Future gain-map / color-mode helpers (no-op until needed) |
 */
object HdrPlatform {
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val isUltraHdrDisplaySupported: Boolean = isAtLeastU

    @ChecksSdkIntAtLeast(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    val isIso21496Preferred: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** Android 16 (Baklava / API 36) optional hooks — currently no extra surface. */
    @ChecksSdkIntAtLeast(api = 36)
    val isApi36HdrExtras: Boolean = Build.VERSION.SDK_INT >= 36

    /**
     * Whether the reader should attempt HDR window color mode for gain-map pages.
     * Conversion to Ultra HDR still runs for always-convert formats (JXR) so pages open.
     */
    fun shouldEnableWindowHdr(readerHdrDisplayPref: Boolean): Boolean =
        readerHdrDisplayPref && isUltraHdrDisplaySupported
}
