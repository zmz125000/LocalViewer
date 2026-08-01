package com.hippo.ehviewer.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi

/**
 * Aves-style window HDR color mode for Ultra HDR / gain-map images.
 * Only enable while an HDR page is on screen; always clear on leave.
 */
fun Activity.supportsScreenHdr(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return displayIsHdr() || resources.configuration.isScreenHdr
}

fun Activity.setHdrColorMode(on: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    window.colorMode = if (on && supportsScreenHdr()) {
        ActivityInfo.COLOR_MODE_HDR
    } else {
        ActivityInfo.COLOR_MODE_DEFAULT
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun Activity.displayIsHdr(): Boolean {
    val d: Display? = display
    return d?.isHdr == true
}
