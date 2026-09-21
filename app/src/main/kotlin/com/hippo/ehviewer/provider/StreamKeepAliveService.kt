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

/**
 * Slim process-rank FGS: keeps [ExternalHttpStreamServer] and stream tokens in RAM.
 *
 * No wake lock — the phone may sleep. Playing holds the live SMB/WebDAV handle; idle
 * is HTTP + session only. Resume is a new Range / URI open.
 *
 * Android may time out a `dataSync` FGS. A timed-out service must stop promptly.
 */
class StreamKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_STREAMING) {
            logcat("StreamKeepAlive") { "notification Stop streaming" }
            StreamKeepAlivePolicy.stopStreaming("notification")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        if (!promoteForeground()) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        logcat("StreamKeepAlive") {
            "Foreground service timed out (type=$fgsType) ${StreamKeepAlivePolicy.runtimeSnapshot()}"
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /**
     * User dismissed the app from Recents while this FGS was running.
     * Without a hard kill, the process (and stream tokens / sticky TCP) can survive.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logcat("StreamKeepAlive") { "onTaskRemoved — user killed app from Recents" }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        StreamKeepAlivePolicy.shutdownAndKillProcess("task_removed")
    }

    private fun promoteForeground(): Boolean {
        val notification = buildNotification()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Throwable) {
            logcat("StreamKeepAlive", e)
            // Do not leave a startForegroundService() instance waiting for promotion.
            // That becomes a foreground-service timeout/ANR a few seconds later.
            false
        }
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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stopStreaming = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, StreamKeepAliveService::class.java).setAction(ACTION_STOP_STREAMING),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_arrow_108dp)
            .setContentTitle(getString(I18nR.string.stream_keepalive_title))
            .setContentText(getString(I18nR.string.stream_keepalive_text))
            .setContentIntent(openApp)
            .addAction(
                R.drawable.ic_pause_108dp,
                getString(I18nR.string.stream_keepalive_stop),
                stopStreaming,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP_STREAMING = "com.hippo.ehviewer.STOP_STREAMING"
        private const val CHANNEL_ID = "stream_keepalive"
        private const val NOTIFICATION_ID = 0x535444 // "STD"
        private const val STOP_REQUEST_CODE = 1

        @Volatile
        private var instance: StreamKeepAliveService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, StreamKeepAliveService::class.java),
                )
            } catch (e: Throwable) {
                logcat("StreamKeepAlive", e)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.stopService(Intent(app, StreamKeepAliveService::class.java))
            }.onFailure { logcat("StreamKeepAlive", it) }
        }
    }
}
