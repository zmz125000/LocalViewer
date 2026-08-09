package com.hippo.ehviewer.provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ehviewer.core.i18n.R as I18nR
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps LocalViewer unfrozen while a network [StreamDocumentProvider] proxy FD is open.
 *
 * External players stream SMB/WebDAV over Fuse in **this** process. Without a foreground
 * service the process is cached after ON_STOP, frozen after a few minutes, and reads
 * stall — playback dies and resume fails because the in-memory token / sticky session
 * is gone. Start when the first network proxy is retained; stop when the last is released.
 */
class StreamKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Throwable) {
            logcat("StreamKeepAlive", e)
            // Fallback without type for odd OEM / older shim paths.
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(I18nR.string.stream_keepalive_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(I18nR.string.stream_keepalive_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_arrow_108dp)
            .setContentTitle(getString(I18nR.string.stream_keepalive_title))
            .setContentText(getString(I18nR.string.stream_keepalive_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "stream_keepalive"
        private const val NOTIFICATION_ID = 0x535444 // "STD"

        private val running = AtomicBoolean(false)

        /** Idempotent start/stop driven by live network proxy FD count. */
        fun setActive(context: Context, active: Boolean) {
            val app = context.applicationContext
            if (active) {
                if (!running.compareAndSet(false, true)) return
                try {
                    ContextCompat.startForegroundService(
                        app,
                        Intent(app, StreamKeepAliveService::class.java),
                    )
                } catch (e: Throwable) {
                    running.set(false)
                    logcat("StreamKeepAlive", e)
                }
            } else {
                if (!running.compareAndSet(true, false)) return
                runCatching {
                    app.stopService(Intent(app, StreamKeepAliveService::class.java))
                }.onFailure { logcat("StreamKeepAlive", it) }
            }
        }
    }
}
