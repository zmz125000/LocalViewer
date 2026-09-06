package com.hippo.ehviewer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ehviewer.core.i18n.R
import java.io.IOException
import splitties.init.appCtx

/**
 * Android 17 (API 37) blocks LAN sockets (SMB, local WebDAV, EasyTier) unless the app
 * declares and is granted [ACCESS_LOCAL_NETWORK]. Missing permission surfaces as a TCP
 * [java.util.concurrent.TimeoutException] rather than a permission error.
 */
object LocalNetworkPermission {
    const val ACCESS_LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK

    val isRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context = appCtx): Boolean {
        if (!isRequired) return true
        return ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun deniedMessage(context: Context = appCtx): String = context.getString(R.string.error_local_network_permission)

    fun requireGranted(context: Context = appCtx) {
        if (!isGranted(context)) {
            throw IOException(deniedMessage(context))
        }
    }
}

context(ctx: Context)
suspend fun ensureLocalNetworkPermission(): Boolean {
    if (!LocalNetworkPermission.isRequired) return true
    if (LocalNetworkPermission.isGranted(ctx)) return true
    return requestPermission(LocalNetworkPermission.ACCESS_LOCAL_NETWORK)
}
