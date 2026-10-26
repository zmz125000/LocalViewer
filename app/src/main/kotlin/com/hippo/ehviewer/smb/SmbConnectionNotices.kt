package com.hippo.ehviewer.smb

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import java.util.concurrent.ConcurrentHashMap
import splitties.init.appCtx

/** Small, rate-limited connection diagnostics for the optional Advanced setting. */
internal object SmbConnectionNotices {
    private const val SUCCESS_INTERVAL_MS = 5_000L
    private const val FAILURE_INTERVAL_MS = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastNoticeAt = ConcurrentHashMap<String, Long>()

    fun connected(host: String) {
        if (!Settings.smbConnectionNotices.value) return
        val address = SmbMdns.dialTargets(host).firstOrNull()?.hostAddress ?: host
        show(host, "connected", SUCCESS_INTERVAL_MS) {
            appCtx.getString(R.string.settings_smb_connection_connected, address)
        }
    }

    fun failed(host: String, error: Throwable) {
        if (!Settings.smbConnectionNotices.value) return
        val address = SmbMdns.dialTargets(host).firstOrNull()?.hostAddress ?: host
        val reason = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            ?.take(120)
            ?: error.javaClass.simpleName
        show(host, "failed", FAILURE_INTERVAL_MS) {
            appCtx.getString(R.string.settings_smb_connection_failed, address, reason)
        }
    }

    private fun show(host: String, kind: String, intervalMs: Long, message: () -> String) {
        val key = "${host.trim().lowercase()}|$kind"
        val now = SystemClock.elapsedRealtime()
        val previous = lastNoticeAt.put(key, now)
        if (previous != null && now - previous < intervalMs) return
        mainHandler.post {
            if (Settings.smbConnectionNotices.value) {
                Toast.makeText(appCtx, message(), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
