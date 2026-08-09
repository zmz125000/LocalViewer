package com.hippo.ehviewer.provider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.util.requestPermission

/**
 * Best-effort notification permission request before handing a network stream to another app.
 *
 * Denial must not block playback: Android still allows the foreground service and exposes it in
 * Active apps, but hides its notification from the drawer on Android 13+.
 */
suspend fun requestStreamNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    runCatching {
        with(context) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.onFailure { logcat("StreamKeepAlive", it) }
}
